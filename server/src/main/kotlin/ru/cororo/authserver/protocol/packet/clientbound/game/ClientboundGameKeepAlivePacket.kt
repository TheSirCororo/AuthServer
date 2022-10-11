package ru.cororo.authserver.protocol.packet.clientbound.game

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec

data class ClientboundGameKeepAlivePacket(
    val keepAliveId: Long
) : Packet {
    override val bound: PacketBound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundGameKeepAlivePacket> {
        override val packetClass = ClientboundGameKeepAlivePacket::class.java

        override fun read(input: ByteBuf): ClientboundGameKeepAlivePacket {
            return ClientboundGameKeepAlivePacket(0)
        }

        override fun write(output: ByteBuf, packet: ClientboundGameKeepAlivePacket) {
            output.writeLong(packet.keepAliveId)
        }
    }
}