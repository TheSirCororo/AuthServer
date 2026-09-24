package ru.cororo.authserver.server.network.handler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.cororo.authserver.api.event.PreLoginEvent
import ru.cororo.authserver.bridge.AuthMode
import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.packet.EncryptionRequestPacket
import ru.cororo.authserver.protocol.packet.EncryptionResponsePacket
import ru.cororo.authserver.protocol.packet.LoginAcknowledgedPacket
import ru.cororo.authserver.protocol.packet.LoginPluginRequestPacket
import ru.cororo.authserver.protocol.packet.LoginPluginResponsePacket
import ru.cororo.authserver.protocol.packet.LoginStartPacket
import ru.cororo.authserver.protocol.packet.LoginSuccessPacket
import ru.cororo.authserver.protocol.packet.SetCompressionPacket
import ru.cororo.authserver.server.AuthServerImpl
import ru.cororo.authserver.server.auth.GameProfile
import ru.cororo.authserver.server.auth.offlineUuid
import ru.cororo.authserver.server.config.ForwardingMode
import ru.cororo.authserver.server.network.Connection
import ru.cororo.authserver.server.network.ForwardedPlayer
import ru.cororo.authserver.server.network.PacketHandler
import ru.cororo.authserver.server.network.VelocityForwarding
import ru.cororo.authserver.server.network.forwardedAddress
import ru.cororo.authserver.server.player.LimboPlayer
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Login state. Steps run on the connection's event loop; lookups (accounts, Mojang, plugin events) run on I/O
 * threads and hop back through [Connection.execute]. Each step accepts only the packet it expects.
 */
class LoginHandler(
    private val server: AuthServerImpl,
    private val connection: Connection,
    /** Player data from legacy (BungeeCord) forwarding; the name arrives with login start. */
    private val legacyForwarded: ForwardedPlayer?,
) : PacketHandler {
    private enum class Step { START, FORWARDING, ENCRYPTION, WAITING, ACKNOWLEDGE, DONE }

    private var step = Step.START
    private lateinit var username: String
    private var mode = AuthMode.OFFLINE
    private val verifyToken = ByteArray(4).also(random::nextBytes)
    private val forwardingMessageId = random.nextInt(Int.MAX_VALUE)
    private var player: LimboPlayer? = null

    override fun handle(packet: Packet) {
        when {
            packet is LoginStartPacket && step == Step.START -> start(packet)
            packet is LoginPluginResponsePacket && step == Step.FORWARDING -> forwarded(packet)
            packet is EncryptionResponsePacket && step == Step.ENCRYPTION -> encrypted(packet)
            packet is LoginAcknowledgedPacket && step == Step.ACKNOWLEDGE -> acknowledged()
            packet is LoginPluginResponsePacket -> Unit // answers to queries we did not send
            else -> connection.close()
        }
    }

    private fun start(packet: LoginStartPacket) {
        username = packet.username
        if (!server.auth.isValidName(username)) return kick("kick-invalid-name")
        when (server.config.proxy.forwarding) {
            ForwardingMode.VELOCITY -> {
                if (connection.version < ProtocolVersion.MINECRAFT_1_13) return kick("kick-proxy-only")
                step = Step.FORWARDING
                connection.send(LoginPluginRequestPacket(forwardingMessageId, VelocityForwarding.CHANNEL, VelocityForwarding.request()))
            }
            ForwardingMode.LEGACY -> {
                val forwarded = requireNotNull(legacyForwarded)
                proxied(forwarded.copy(username = username))
            }
            ForwardingMode.NONE -> decideMode()
        }
    }

    // ------------------------------------------------------------------------------------------------ behind a proxy

    private fun forwarded(packet: LoginPluginResponsePacket) {
        if (packet.messageId != forwardingMessageId) return
        val forwarded = packet.data?.let { VelocityForwarding.read(it, server.config.proxy.secret) }
            ?: return kick("kick-proxy-only").also { logger.warn("Rejected {}: invalid Velocity forwarding data", connection.address) }
        connection.address = forwardedAddress(forwarded.address, connection.address.port)
        proxied(forwarded)
    }

    /** The proxy already authenticated licensed players; offline ones carry vanilla's offline UUID. */
    private fun proxied(forwarded: ForwardedPlayer) {
        username = forwarded.username
        val licensed = forwarded.uuid != offlineUuid(forwarded.username)
        // With licensed login disabled even Mojang-verified players prove themselves with a password.
        mode = if (licensed && server.config.authentication.licensedLogin) AuthMode.ONLINE else AuthMode.OFFLINE
        step = Step.WAITING
        preLogin(fixedMode = true) { finish(GameProfile(forwarded.uuid, forwarded.username, forwarded.properties)) }
    }

    // ------------------------------------------------------------------------------------------------ standalone

    private fun decideMode() {
        step = Step.WAITING
        server.scope.launch(Dispatchers.IO) {
            val decided = runCatching { server.premium.mode(username) }.getOrElse {
                logger.warn("Could not decide the login mode of {}", username, it)
                AuthMode.OFFLINE
            }
            connection.execute {
                mode = decided
                preLogin(fixedMode = false) {
                    val encrypt = mode == AuthMode.ONLINE ||
                        (server.config.authentication.encryptOfflineConnections && connection.version >= ProtocolVersion.MINECRAFT_1_20_5)
                    if (encrypt) {
                        step = Step.ENCRYPTION
                        connection.send(EncryptionRequestPacket("", server.keyPair.public.encoded, verifyToken, mode == AuthMode.ONLINE))
                    } else {
                        finish(GameProfile(offlineUuid(username), username))
                    }
                }
            }
        }
    }

    private fun encrypted(packet: EncryptionResponsePacket) {
        step = Step.WAITING
        val secret = try {
            val token = decrypt(requireNotNull(packet.verifyToken) { "Signed login is not supported" })
            require(MessageDigest.isEqual(token, verifyToken)) { "Verify token mismatch" }
            SecretKeySpec(decrypt(packet.sharedSecret).also { require(it.size == 16) }, "AES")
        } catch (exception: Exception) {
            logger.debug("Invalid encryption response from {}: {}", connection.address, exception.message)
            return connection.close()
        }
        connection.enableEncryption(secret)
        if (mode == AuthMode.OFFLINE) return finish(GameProfile(offlineUuid(username), username))
        val hash = BigInteger(MessageDigest.getInstance("SHA-1").run {
            update(secret.encoded)
            digest(server.keyPair.public.encoded)
        }).toString(16)
        server.scope.launch(Dispatchers.IO) {
            val profile = runCatching { withTimeout(10_000) { server.mojang.hasJoined(username, hash) } }
            connection.execute {
                profile.fold(
                    onSuccess = { verified -> if (verified == null) kick("kick-premium-required") else finish(verified) },
                    onFailure = {
                        logger.warn("Mojang session check failed for {}: {}", username, it.toString())
                        kick("kick-mojang-unavailable")
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ completion

    /** Lets plugins deny the login (and pick the mode when the server decides it), then continues on the event loop. */
    private fun preLogin(fixedMode: Boolean, next: () -> Unit) {
        server.scope.launch(Dispatchers.IO) {
            val event = server.events.post(PreLoginEvent(username, connection.address, connection.version, mode))
            connection.execute {
                event.denyReason?.let { return@execute connection.disconnect(it) }
                if (!fixedMode) mode = event.mode
                next()
            }
        }
    }

    private fun finish(profile: GameProfile) {
        if (!connection.isActive) return
        if (server.player(profile.name) != null || server.player(profile.uuid) != null) return kick("kick-already-connected")
        val threshold = server.config.network.compressionThreshold
        if (threshold >= 0) {
            connection.send(SetCompressionPacket(threshold))
            connection.enableCompression(threshold)
        }
        val player = LimboPlayer(connection, profile.name, profile.uuid, profile.properties, mode, server.world.spawn)
        if (!server.addPlayer(player)) return kick("kick-already-connected")
        this.player = player
        connection.send(LoginSuccessPacket(profile.uuid, profile.name, profile.properties, UUID.randomUUID()))
        if (connection.version >= ProtocolVersion.MINECRAFT_1_20_2) {
            step = Step.ACKNOWLEDGE
        } else {
            step = Step.DONE
            PlayHandler(server, connection, player).start()
        }
    }

    private fun acknowledged() {
        step = Step.DONE
        val configuration = ConfigurationHandler(server, connection, requireNotNull(player))
        connection.switchState(ProtocolState.CONFIGURATION, configuration)
        configuration.start()
    }

    override fun disconnected() {
        player?.let(server::removePlayer)
    }

    private fun kick(key: String) = connection.disconnect(server.messages.render(Locale.ENGLISH, key, "versions" to HandshakeHandler.SUPPORTED_VERSIONS))

    private fun decrypt(bytes: ByteArray): ByteArray = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
        init(Cipher.DECRYPT_MODE, server.keyPair.private)
        doFinal(bytes)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(LoginHandler::class.java)
        val random = SecureRandom()
    }
}
