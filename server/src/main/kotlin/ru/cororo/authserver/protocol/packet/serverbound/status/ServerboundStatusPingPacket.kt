package ru.cororo.authserver.protocol.packet.serverbound.status

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec

data class ServerboundStatusPingPacket(
    val payload: Long
) : Packet {
    override val bound = PacketBound.SERVER

    companion object : PacketCodec<ServerboundStatusPingPacket> {
        override val packetClass = ServerboundStatusPingPacket::class.java

        override fun write(output: ByteBuf, packet: ServerboundStatusPingPacket) {}

        override fun read(input: ByteBuf): ServerboundStatusPingPacket {
            return ServerboundStatusPingPacket(input.readLong())
        }
    }
}