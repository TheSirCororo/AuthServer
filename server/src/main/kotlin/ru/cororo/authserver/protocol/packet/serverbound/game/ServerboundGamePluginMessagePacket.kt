package ru.cororo.authserver.protocol.packet.serverbound.game

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.readByteArray
import ru.cororo.authserver.util.readString

data class ServerboundGamePluginMessagePacket(
    val channel: String,
    val data: ByteArray
) : Packet {
    override val bound = PacketBound.SERVER

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ServerboundGamePluginMessagePacket

        if (channel != other.channel) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = channel.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object : PacketCodec<ServerboundGamePluginMessagePacket> {
        override val packetClass = ServerboundGamePluginMessagePacket::class.java

        override fun write(output: ByteBuf, packet: ServerboundGamePluginMessagePacket) {

        }

        override fun read(input: ByteBuf): ServerboundGamePluginMessagePacket {
            return ServerboundGamePluginMessagePacket(input.readString(), input.readByteArray())
        }
    }
}