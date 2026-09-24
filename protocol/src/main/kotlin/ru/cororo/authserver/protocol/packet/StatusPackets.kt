package ru.cororo.authserver.protocol.packet

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.EmptyCodec
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ServerboundPacket
import ru.cororo.authserver.protocol.buffer.readString
import ru.cororo.authserver.protocol.buffer.writeString

data object StatusRequestPacket : ServerboundPacket {
    val Codec = EmptyCodec(this)
}

/** Server list response; [json] is the complete status document. */
data class StatusResponsePacket(val json: String) : ClientboundPacket {
    companion object Codec : PacketCodec<StatusResponsePacket> {
        override fun encode(buffer: ByteBuf, packet: StatusResponsePacket, version: ProtocolVersion) =
            buffer.writeString(packet.json)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = StatusResponsePacket(buffer.readString())
    }
}

data class StatusPingPacket(val payload: Long) : ServerboundPacket {
    companion object Codec : PacketCodec<StatusPingPacket> {
        override fun encode(buffer: ByteBuf, packet: StatusPingPacket, version: ProtocolVersion) {
            buffer.writeLong(packet.payload)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = StatusPingPacket(buffer.readLong())
    }
}

data class StatusPongPacket(val payload: Long) : ClientboundPacket {
    companion object Codec : PacketCodec<StatusPongPacket> {
        override fun encode(buffer: ByteBuf, packet: StatusPongPacket, version: ProtocolVersion) {
            buffer.writeLong(packet.payload)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = StatusPongPacket(buffer.readLong())
    }
}
