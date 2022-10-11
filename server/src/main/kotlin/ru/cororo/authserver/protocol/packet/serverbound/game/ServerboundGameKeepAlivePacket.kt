package ru.cororo.authserver.protocol.packet.serverbound.game

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec

data class ServerboundGameKeepAlivePacket(
    val keepAliveId: Long
) : Packet {
    override val bound: PacketBound = PacketBound.SERVER

    companion object : PacketCodec<ServerboundGameKeepAlivePacket> {
        override val packetClass = ServerboundGameKeepAlivePacket::class.java

        override fun read(input: ByteBuf): ServerboundGameKeepAlivePacket {
            return ServerboundGameKeepAlivePacket(0)
        }

        override fun write(output: ByteBuf, packet: ServerboundGameKeepAlivePacket) {
            output.writeLong(System.currentTimeMillis())
        }
    }
}