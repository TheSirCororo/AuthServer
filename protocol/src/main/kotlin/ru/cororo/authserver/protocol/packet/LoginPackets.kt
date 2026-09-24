package ru.cororo.authserver.protocol.packet

import io.netty.buffer.ByteBuf
import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.EmptyCodec
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_16
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19_1
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19_3
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_5
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_21_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_26_2
import ru.cororo.authserver.protocol.ServerboundPacket
import ru.cororo.authserver.protocol.buffer.readByteArray
import ru.cororo.authserver.protocol.buffer.readIdentifier
import ru.cororo.authserver.protocol.buffer.readJsonComponent
import ru.cororo.authserver.protocol.buffer.readList
import ru.cororo.authserver.protocol.buffer.readOptional
import ru.cororo.authserver.protocol.buffer.readRemainingBytes
import ru.cororo.authserver.protocol.buffer.readString
import ru.cororo.authserver.protocol.buffer.readUuid
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeByteArray
import ru.cororo.authserver.protocol.buffer.writeIdentifier
import ru.cororo.authserver.protocol.buffer.writeJsonComponent
import ru.cororo.authserver.protocol.buffer.writeList
import ru.cororo.authserver.protocol.buffer.writeOptional
import ru.cororo.authserver.protocol.buffer.writeString
import ru.cororo.authserver.protocol.buffer.writeUuid
import ru.cororo.authserver.protocol.buffer.writeVarInt
import java.util.UUID

private const val MAX_USERNAME_LENGTH = 16
private const val MAX_PLUGIN_PAYLOAD = 1 shl 20

val NIL_UUID = UUID(0, 0)

/** Signed game profile property, e.g. `textures`. */
data class ProfileProperty(val name: String, val value: String, val signature: String? = null)

internal fun ByteBuf.writeProperties(properties: List<ProfileProperty>) = writeList(properties) { property ->
    writeString(property.name, 64)
    writeString(property.value)
    writeOptional(property.signature) { writeString(it, 1024) }
}

internal fun ByteBuf.readProperties(): List<ProfileProperty> = readList(16) {
    ProfileProperty(readString(64), readString(), readOptional { readString(1024) })
}

// ---------------------------------------------------------------------------------------------------- clientbound

/** Login-phase disconnect; always a JSON component, even after 1.20.3. */
data class LoginDisconnectPacket(val reason: Component) : ClientboundPacket {
    companion object Codec : PacketCodec<LoginDisconnectPacket> {
        override fun encode(buffer: ByteBuf, packet: LoginDisconnectPacket, version: ProtocolVersion) =
            buffer.writeJsonComponent(packet.reason, version)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            LoginDisconnectPacket(buffer.readJsonComponent(version))
    }
}

class EncryptionRequestPacket(
    val serverId: String,
    val publicKey: ByteArray,
    val verifyToken: ByteArray,
    /** Since 1.20.5: whether the client must contact Mojang's session server. */
    val shouldAuthenticate: Boolean = true,
) : ClientboundPacket {
    companion object Codec : PacketCodec<EncryptionRequestPacket> {
        override fun encode(buffer: ByteBuf, packet: EncryptionRequestPacket, version: ProtocolVersion) {
            buffer.writeString(packet.serverId, 20)
            buffer.writeByteArray(packet.publicKey)
            buffer.writeByteArray(packet.verifyToken)
            if (version >= MINECRAFT_1_20_5) buffer.writeBoolean(packet.shouldAuthenticate)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = EncryptionRequestPacket(
            buffer.readString(20), buffer.readByteArray(), buffer.readByteArray(),
            if (version >= MINECRAFT_1_20_5) buffer.readBoolean() else true,
        )
    }
}

data class LoginSuccessPacket(
    val uuid: UUID,
    val username: String,
    val properties: List<ProfileProperty> = emptyList(),
    /** Since 26.2: identifies the play session; older versions ignore it. */
    val sessionId: UUID = NIL_UUID,
) : ClientboundPacket {
    companion object Codec : PacketCodec<LoginSuccessPacket> {
        override fun encode(buffer: ByteBuf, packet: LoginSuccessPacket, version: ProtocolVersion) {
            if (version >= MINECRAFT_1_16) buffer.writeUuid(packet.uuid) else buffer.writeString(packet.uuid.toString(), 36)
            buffer.writeString(packet.username, MAX_USERNAME_LENGTH)
            if (version >= MINECRAFT_1_19) buffer.writeProperties(packet.properties)
            // 1.20.5 - 1.21.1 carried "strict error handling"; vanilla always sends true.
            if (version >= MINECRAFT_1_20_5 && version < MINECRAFT_1_21_2) buffer.writeBoolean(true)
            if (version >= MINECRAFT_26_2) buffer.writeUuid(packet.sessionId)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): LoginSuccessPacket {
            val uuid = if (version >= MINECRAFT_1_16) buffer.readUuid() else UUID.fromString(buffer.readString(36))
            val username = buffer.readString(MAX_USERNAME_LENGTH)
            val properties = if (version >= MINECRAFT_1_19) buffer.readProperties() else emptyList()
            if (version >= MINECRAFT_1_20_5 && version < MINECRAFT_1_21_2) buffer.readBoolean()
            val sessionId = if (version >= MINECRAFT_26_2) buffer.readUuid() else NIL_UUID
            return LoginSuccessPacket(uuid, username, properties, sessionId)
        }
    }
}

data class SetCompressionPacket(val threshold: Int) : ClientboundPacket {
    companion object Codec : PacketCodec<SetCompressionPacket> {
        override fun encode(buffer: ByteBuf, packet: SetCompressionPacket, version: ProtocolVersion) =
            buffer.writeVarInt(packet.threshold)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = SetCompressionPacket(buffer.readVarInt())
    }
}

/** Custom login query (1.13+), used for Velocity modern forwarding. */
class LoginPluginRequestPacket(val messageId: Int, val channel: String, val data: ByteArray) : ClientboundPacket {
    companion object Codec : PacketCodec<LoginPluginRequestPacket> {
        override fun encode(buffer: ByteBuf, packet: LoginPluginRequestPacket, version: ProtocolVersion) {
            buffer.writeVarInt(packet.messageId)
            buffer.writeIdentifier(packet.channel)
            buffer.writeBytes(packet.data)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = LoginPluginRequestPacket(
            buffer.readVarInt(), buffer.readIdentifier(), buffer.readRemainingBytes(MAX_PLUGIN_PAYLOAD),
        )
    }
}

// ---------------------------------------------------------------------------------------------------- serverbound

data class LoginStartPacket(val username: String, val uuid: UUID? = null) : ServerboundPacket {
    companion object Codec : PacketCodec<LoginStartPacket> {
        override fun encode(buffer: ByteBuf, packet: LoginStartPacket, version: ProtocolVersion) {
            buffer.writeString(packet.username, MAX_USERNAME_LENGTH)
            when {
                version >= MINECRAFT_1_20_2 -> buffer.writeUuid(packet.uuid ?: NIL_UUID)
                version >= MINECRAFT_1_19_3 -> buffer.writeOptional(packet.uuid) { writeUuid(it) }
                version >= MINECRAFT_1_19_1 -> {
                    buffer.writeBoolean(false) // no chat signing key
                    buffer.writeOptional(packet.uuid) { writeUuid(it) }
                }
                version >= MINECRAFT_1_19 -> buffer.writeBoolean(false)
            }
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): LoginStartPacket {
            val username = buffer.readString(MAX_USERNAME_LENGTH)
            val uuid = when {
                version >= MINECRAFT_1_20_2 -> buffer.readUuid()
                version >= MINECRAFT_1_19_3 -> buffer.readOptional { readUuid() }
                version >= MINECRAFT_1_19 -> {
                    buffer.readOptional { skipSigningKey() }
                    if (version >= MINECRAFT_1_19_1) buffer.readOptional { readUuid() } else null
                }
                else -> null
            }
            return LoginStartPacket(username, uuid)
        }

        /** 1.19 - 1.19.2 profile public key: expiry, key, Mojang signature. */
        private fun ByteBuf.skipSigningKey() {
            readLong()
            readByteArray(512)
            readByteArray(4096)
        }
    }
}

class EncryptionResponsePacket(val sharedSecret: ByteArray, val verifyToken: ByteArray?) : ServerboundPacket {
    companion object Codec : PacketCodec<EncryptionResponsePacket> {
        override fun encode(buffer: ByteBuf, packet: EncryptionResponsePacket, version: ProtocolVersion) {
            buffer.writeByteArray(packet.sharedSecret)
            if (version >= MINECRAFT_1_19 && version < MINECRAFT_1_19_3) buffer.writeBoolean(true)
            buffer.writeByteArray(requireNotNull(packet.verifyToken))
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): EncryptionResponsePacket {
            val secret = buffer.readByteArray(256)
            // 1.19 - 1.19.2 clients with a chat signing key send a salted signature instead of the token.
            // Such responses cannot prove key ownership to us, so they are surfaced as a missing token.
            if (version >= MINECRAFT_1_19 && version < MINECRAFT_1_19_3 && !buffer.readBoolean()) {
                buffer.readLong()
                buffer.readByteArray(4096)
                return EncryptionResponsePacket(secret, null)
            }
            return EncryptionResponsePacket(secret, buffer.readByteArray(256))
        }
    }
}

class LoginPluginResponsePacket(val messageId: Int, val data: ByteArray?) : ServerboundPacket {
    companion object Codec : PacketCodec<LoginPluginResponsePacket> {
        override fun encode(buffer: ByteBuf, packet: LoginPluginResponsePacket, version: ProtocolVersion) {
            buffer.writeVarInt(packet.messageId)
            buffer.writeBoolean(packet.data != null)
            packet.data?.let(buffer::writeBytes)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = LoginPluginResponsePacket(
            buffer.readVarInt(), if (buffer.readBoolean()) buffer.readRemainingBytes(MAX_PLUGIN_PAYLOAD) else null,
        )
    }
}

data object LoginAcknowledgedPacket : ServerboundPacket {
    val Codec = EmptyCodec(this)
}
