package ru.cororo.authserver.protocol.packet

import io.netty.buffer.ByteBuf
import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_12_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_13
import ru.cororo.authserver.protocol.ServerboundPacket
import ru.cororo.authserver.protocol.buffer.readComponent
import ru.cororo.authserver.protocol.buffer.readRemainingBytes
import ru.cororo.authserver.protocol.buffer.readString
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeComponent
import ru.cororo.authserver.protocol.buffer.writeString
import ru.cororo.authserver.protocol.buffer.writeVarInt

// Packets shared by the configuration (1.20.2+) and play states.

private const val MAX_SERVERBOUND_PAYLOAD = 32767
private const val MAX_CLIENTBOUND_PAYLOAD = 1 shl 20

/** Channel names are free-form strings before 1.13 and namespaced identifiers afterwards. */
private fun channelLimit(version: ProtocolVersion) = if (version >= MINECRAFT_1_13) 32767 else 20

private fun ByteBuf.writeKeepAliveId(id: Long, version: ProtocolVersion) {
    if (version >= MINECRAFT_1_12_2) writeLong(id) else writeVarInt(id.toInt())
}

private fun ByteBuf.readKeepAliveId(version: ProtocolVersion): Long =
    if (version >= MINECRAFT_1_12_2) readLong() else readVarInt().toLong()

data class ClientboundKeepAlivePacket(val id: Long) : ClientboundPacket {
    companion object Codec : PacketCodec<ClientboundKeepAlivePacket> {
        override fun encode(buffer: ByteBuf, packet: ClientboundKeepAlivePacket, version: ProtocolVersion) =
            buffer.writeKeepAliveId(packet.id, version)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            ClientboundKeepAlivePacket(buffer.readKeepAliveId(version))
    }
}

data class ServerboundKeepAlivePacket(val id: Long) : ServerboundPacket {
    companion object Codec : PacketCodec<ServerboundKeepAlivePacket> {
        override fun encode(buffer: ByteBuf, packet: ServerboundKeepAlivePacket, version: ProtocolVersion) =
            buffer.writeKeepAliveId(packet.id, version)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            ServerboundKeepAlivePacket(buffer.readKeepAliveId(version))
    }
}

class ClientboundPluginMessagePacket(val channel: String, val data: ByteArray) : ClientboundPacket {
    companion object Codec : PacketCodec<ClientboundPluginMessagePacket> {
        override fun encode(buffer: ByteBuf, packet: ClientboundPluginMessagePacket, version: ProtocolVersion) {
            buffer.writeString(packet.channel, channelLimit(version))
            buffer.writeBytes(packet.data)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = ClientboundPluginMessagePacket(
            buffer.readString(channelLimit(version)), buffer.readRemainingBytes(MAX_CLIENTBOUND_PAYLOAD),
        )
    }
}

class ServerboundPluginMessagePacket(val channel: String, val data: ByteArray) : ServerboundPacket {
    companion object Codec : PacketCodec<ServerboundPluginMessagePacket> {
        override fun encode(buffer: ByteBuf, packet: ServerboundPluginMessagePacket, version: ProtocolVersion) {
            buffer.writeString(packet.channel, channelLimit(version))
            buffer.writeBytes(packet.data)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = ServerboundPluginMessagePacket(
            buffer.readString(channelLimit(version)), buffer.readRemainingBytes(MAX_SERVERBOUND_PAYLOAD),
        )
    }
}

/** Configuration/play disconnect; JSON until 1.20.3, NBT afterwards. */
data class DisconnectPacket(val reason: Component) : ClientboundPacket {
    companion object Codec : PacketCodec<DisconnectPacket> {
        override fun encode(buffer: ByteBuf, packet: DisconnectPacket, version: ProtocolVersion) =
            buffer.writeComponent(packet.reason, version)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = DisconnectPacket(buffer.readComponent(version))
    }
}

/** Client settings. Only the locale matters to the auth server; the rest is skipped. */
data class ClientInformationPacket(val locale: String, val viewDistance: Int) : ServerboundPacket {
    companion object Codec : PacketCodec<ClientInformationPacket> {
        override fun encode(buffer: ByteBuf, packet: ClientInformationPacket, version: ProtocolVersion) {
            buffer.writeString(packet.locale, 16)
            buffer.writeByte(packet.viewDistance)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): ClientInformationPacket {
            val packet = ClientInformationPacket(buffer.readString(16), buffer.readByte().toInt())
            buffer.skipBytes(buffer.readableBytes())
            return packet
        }
    }
}

/** Configuration-state ping (1.20.2+). */
data class PingPacket(val id: Int) : ClientboundPacket {
    companion object Codec : PacketCodec<PingPacket> {
        override fun encode(buffer: ByteBuf, packet: PingPacket, version: ProtocolVersion) {
            buffer.writeInt(packet.id)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = PingPacket(buffer.readInt())
    }
}

data class PongPacket(val id: Int) : ServerboundPacket {
    companion object Codec : PacketCodec<PongPacket> {
        override fun encode(buffer: ByteBuf, packet: PongPacket, version: ProtocolVersion) {
            buffer.writeInt(packet.id)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = PongPacket(buffer.readInt())
    }
}

/** Moves the client to another server (1.20.5+). */
data class TransferPacket(val host: String, val port: Int) : ClientboundPacket {
    companion object Codec : PacketCodec<TransferPacket> {
        override fun encode(buffer: ByteBuf, packet: TransferPacket, version: ProtocolVersion) {
            buffer.writeString(packet.host)
            buffer.writeVarInt(packet.port)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = TransferPacket(buffer.readString(), buffer.readVarInt())
    }
}
