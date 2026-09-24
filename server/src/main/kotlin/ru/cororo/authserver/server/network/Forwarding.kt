package ru.cororo.authserver.server.network

import com.google.gson.JsonParser
import io.netty.buffer.Unpooled
import ru.cororo.authserver.protocol.buffer.readString
import ru.cororo.authserver.protocol.buffer.readUuid
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.packet.ProfileProperty
import ru.cororo.authserver.server.auth.parseUuid
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Player data a proxy forwarded. */
data class ForwardedPlayer(val address: String, val uuid: UUID, val username: String, val properties: List<ProfileProperty>)

/**
 * Velocity modern forwarding: the backend asks for `velocity:player_info` during login and receives
 * `HMAC-SHA256(secret, payload) || payload`.
 */
object VelocityForwarding {
    const val CHANNEL = "velocity:player_info"

    /** Plain profile forwarding, no chat signing keys. */
    private const val MODERN_DEFAULT = 1

    fun request(): ByteArray = byteArrayOf(MODERN_DEFAULT.toByte())

    /** Returns the forwarded player, or `null` when the signature does not match [secret]. */
    fun read(response: ByteArray, secret: String): ForwardedPlayer? {
        if (response.size <= 32) return null
        val signature = response.copyOfRange(0, 32)
        val payload = response.copyOfRange(32, response.size)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        if (!MessageDigest.isEqual(signature, mac.doFinal(payload))) return null
        val buffer = Unpooled.wrappedBuffer(payload)
        val version = buffer.readVarInt()
        require(version >= MODERN_DEFAULT) { "Unsupported forwarding version $version" }
        val address = buffer.readString(255)
        val uuid = buffer.readUuid()
        val username = buffer.readString(16)
        val properties = List(buffer.readVarInt()) {
            ProfileProperty(buffer.readString(), buffer.readString(), if (buffer.readBoolean()) buffer.readString() else null)
        }
        return ForwardedPlayer(address, uuid, username, properties)
    }
}

/**
 * BungeeCord-style forwarding: the handshake host is `host\0address\0uuid\0properties-json`.
 * BungeeGuard adds a `bungeeguard-token` property that must equal the shared secret.
 */
object LegacyForwarding {
    private const val TOKEN_PROPERTY = "bungeeguard-token"

    sealed interface Result {
        data class Accepted(val player: ForwardedPlayer) : Result
        data object Missing : Result
        data object BadToken : Result
    }

    fun read(handshakeAddress: String, secret: String?): Result {
        val parts = handshakeAddress.split('\u0000')
        if (parts.size < 3) return Result.Missing
        val properties = parts.getOrNull(3)?.let { json ->
            JsonParser.parseString(json).asJsonArray.map { element ->
                val property = element.asJsonObject
                ProfileProperty(property["name"].asString, property["value"].asString, property["signature"]?.asString)
            }
        }.orEmpty()
        if (!secret.isNullOrEmpty()) {
            val token = properties.singleOrNull { it.name == TOKEN_PROPERTY }?.value ?: return Result.BadToken
            if (!MessageDigest.isEqual(token.toByteArray(), secret.toByteArray())) return Result.BadToken
        }
        // The username is only known at login start; it is filled in by the login handler.
        return Result.Accepted(ForwardedPlayer(parts[1], parseUuid(parts[2]), "", properties.filter { it.name != TOKEN_PROPERTY }))
    }
}

/** A forwarded client address; host names are kept unresolved rather than looked up. */
internal fun forwardedAddress(address: String, port: Int): InetSocketAddress =
    runCatching { InetSocketAddress(InetAddress.ofLiteral(address), port) }.getOrElse { InetSocketAddress.createUnresolved(address, port) }
