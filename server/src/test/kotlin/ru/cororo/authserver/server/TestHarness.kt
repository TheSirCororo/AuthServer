package ru.cororo.authserver.server

import io.netty.buffer.Unpooled
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.cororo.authserver.bridge.BridgeCodec
import ru.cororo.authserver.bridge.BridgeMessage
import ru.cororo.authserver.probe.ProbeConnection
import ru.cororo.authserver.protocol.MinecraftPackets
import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.PacketDirection
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.buffer.ItemStack
import ru.cororo.authserver.protocol.packet.*
import ru.cororo.authserver.protocol.packet.play.*
import ru.cororo.authserver.server.auth.GameProfile
import ru.cororo.authserver.server.auth.MojangApi
import ru.cororo.authserver.server.config.AuthenticationConfig
import ru.cororo.authserver.server.config.DatabaseSection
import ru.cororo.authserver.server.config.HashingConfig
import ru.cororo.authserver.server.config.NetworkConfig
import ru.cororo.authserver.server.config.ServerConfig
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.deleteRecursively

/** Licensed accounts known to the fake Mojang; `hasJoined` trusts every hash, as there is no real client. */
class FakeMojang(private val licensed: Map<String, UUID> = emptyMap()) : MojangApi {
    override suspend fun hasJoined(username: String, serverHash: String): GameProfile? =
        licensed.entries.firstOrNull { it.key.equals(username, ignoreCase = true) }?.let { GameProfile(it.value, it.key) }

    override suspend fun accountExists(username: String): Boolean = licensed.keys.any { it.equals(username, ignoreCase = true) }
}

/** A real server on a free port with a throw-away SQLite database and fast hashing. */
class TestServer(
    mojang: MojangApi = FakeMojang(),
    private val directory: Path = Files.createTempDirectory("authserver-test"),
    configure: (ServerConfig) -> ServerConfig = { it },
) : AutoCloseable {
    val port = ServerSocket(0).use { it.localPort }
    val config = configure(ServerConfig(
        network = NetworkConfig(host = "127.0.0.1", port = port, compressionThreshold = 64),
        database = DatabaseSection(file = "auth.db"),
        authentication = AuthenticationConfig(hashing = HashingConfig(memoryKib = 1024, iterations = 1), reminderSeconds = 60),
    ))
    val server = AuthServerImpl(config, directory, mojang).also { it.start() }

    fun client(version: ProtocolVersion, username: String) = TestClient(port, version, username)

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    override fun close() {
        server.stop()
        directory.deleteRecursively()
    }
}

/**
 * Minimal game client: logs in, answers keep-alives and teleports, and records chat and plugin messages.
 * Hooks let tests act as a proxy (forwarding) during login.
 */
class TestClient(port: Int, val version: ProtocolVersion, val username: String) : AutoCloseable {
    private val connection = ProbeConnection("127.0.0.1", port, version)
    val chat = mutableListOf<String>()
    val pluginMessages = mutableListOf<ClientboundPluginMessagePacket>()
    var disconnectReason: String? = null
        private set
    var joined: JoinGamePacket? = null
        private set
    var loginSuccess: LoginSuccessPacket? = null
        private set
    var encrypted = false
        private set
    /** Latest contents of every window the server sent. */
    val windows = HashMap<Int, MutableList<ItemStack?>>()
    var screen: OpenScreenPacket? = null
        private set

    /** Answers `Login Plugin Request`s, e.g. with Velocity forwarding data. */
    var onLoginQuery: (LoginPluginRequestPacket) -> ByteArray? = { null }

    fun connect(handshakeAddress: String = "localhost", uuid: UUID = UUID(0, 0)): TestClient {
        connection.send(HandshakePacket(version.protocol, handshakeAddress, 25565, HandshakePacket.Intent.LOGIN))
        connection.state = ProtocolState.LOGIN
        connection.send(LoginStartPacket(username, uuid))
        while (connection.state == ProtocolState.LOGIN) {
            when (val packet = next() ?: return this) {
                is SetCompressionPacket -> connection.enableCompression(packet.threshold)
                is EncryptionRequestPacket -> encrypt(packet)
                is LoginPluginRequestPacket -> connection.send(LoginPluginResponsePacket(packet.messageId, onLoginQuery(packet)))
                is LoginSuccessPacket -> {
                    loginSuccess = packet
                    if (version >= ProtocolVersion.MINECRAFT_1_20_2) {
                        connection.send(LoginAcknowledgedPacket)
                        connection.state = ProtocolState.CONFIGURATION
                        configure()
                    } else {
                        connection.state = ProtocolState.PLAY
                    }
                }
            }
        }
        while (joined == null && disconnectReason == null) next() ?: break
        return this
    }

    private fun configure() {
        while (connection.state == ProtocolState.CONFIGURATION) {
            when (val packet = next() ?: return) {
                is ClientboundKnownPacksPacket -> connection.send(ServerboundKnownPacksPacket(packet.packs))
                is FinishConfigurationPacket -> {
                    connection.send(FinishConfigurationAckPacket)
                    connection.state = ProtocolState.PLAY
                }
            }
        }
    }

    private fun encrypt(request: EncryptionRequestPacket) {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(request.publicKey))
        val secret = ByteArray(16).also(SecureRandom()::nextBytes)
        fun rsa(bytes: ByteArray) = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(bytes)
        }
        connection.send(EncryptionResponsePacket(rsa(secret), rsa(request.verifyToken)))
        connection.enableEncryption(SecretKeySpec(secret, "AES"))
        encrypted = true
    }

    /** Reads packets until [condition] holds for a chat line, or fails after [timeoutMillis]. */
    fun awaitChat(timeoutMillis: Long = 10_000, condition: (String) -> Boolean): String {
        chat.firstOrNull(condition)?.let { return it }
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            next() ?: break
            chat.firstOrNull(condition)?.let { return it }
        }
        error("No matching chat message; got $chat, disconnect: $disconnectReason")
    }

    fun awaitBridge(codec: BridgeCodec, timeoutMillis: Long = 10_000): BridgeMessage {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            pluginMessages.firstOrNull { it.channel == BridgeCodec.CHANNEL }?.let { return codec.decode(it.data, System.currentTimeMillis()) }
            next() ?: break
        }
        error("No bridge message; chat $chat, disconnect: $disconnectReason")
    }

    /** Reads until the server closes the connection and returns the disconnect reason. */
    fun awaitDisconnect(timeoutMillis: Long = 15_000): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (disconnectReason == null && System.currentTimeMillis() < deadline) next() ?: break
        return checkNotNull(disconnectReason) { "Not disconnected; chat $chat" }
    }

    /** Reads packets until [condition] holds. */
    fun await(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met; chat $chat, disconnect: $disconnectReason" }
            next() ?: error("Disconnected: $disconnectReason")
        }
    }

    fun click(windowId: Int, slot: Int) = connection.send(ClickContainerPacket(windowId, slot, 0))

    fun closeScreen(windowId: Int) = connection.send(ServerboundCloseContainerPacket(windowId))

    /** Right click in the air with the held item; 1.8 reports it as a block placement. */
    fun useItem() = connection.send(if (version >= ProtocolVersion.MINECRAFT_1_9) UseItemPacket(0) else UseItemOnPacket(0))

    fun command(command: String) = connection.send(
        if (version >= ProtocolVersion.MINECRAFT_1_19) ChatCommandPacket(command) else ChatPacket("/$command"),
    )

    /** Next decoded packet (unknown and encode-only ones are skipped), or `null` when the connection closed. */
    private fun next(): Packet? {
        while (true) {
            val frame = try {
                connection.read()
            } catch (_: java.io.IOException) {
                return null
            }
            val codec = MinecraftPackets.registry.table(version, connection.state, PacketDirection.CLIENTBOUND).codec(frame.id) ?: continue
            val packet = try {
                codec.decode(Unpooled.wrappedBuffer(frame.body), version)
            } catch (_: UnsupportedOperationException) {
                continue
            }
            when (packet) {
                is SystemChatPacket -> chat += plain(packet.message)
                is ClientboundPluginMessagePacket -> pluginMessages += packet
                is LoginDisconnectPacket -> disconnectReason = plain(packet.reason)
                is DisconnectPacket -> disconnectReason = plain(packet.reason)
                is JoinGamePacket -> joined = packet
                is ContainerContentPacket -> windows[packet.windowId] = packet.items.toMutableList()
                is ContainerSlotPacket -> windows[packet.windowId]?.let { if (packet.slot in it.indices) it[packet.slot] = packet.item }
                is OpenScreenPacket -> screen = packet
                is CloseContainerPacket -> if (screen?.windowId == packet.windowId) screen = null
                is ClientboundKeepAlivePacket -> connection.send(ServerboundKeepAlivePacket(packet.id))
                is PlayerPositionPacket -> if (version >= ProtocolVersion.MINECRAFT_1_9) {
                    connection.send(TeleportConfirmPacket(packet.teleportId, packet.x, packet.y, packet.z, packet.yaw, packet.pitch))
                }
            }
            return packet
        }
    }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    override fun close() = connection.close()
}
