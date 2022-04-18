package ru.cororo.authserver.protocol.packet.clientbound.status

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.writeString

data class ClientboundStatusResponsePacket(
    val response: String
) : Packet {
    override val bound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundStatusResponsePacket> {
        override val packetClass = ClientboundStatusResponsePacket::class.java

        override fun write(output: ByteBuf, packet: ClientboundStatusResponsePacket) {
            output.writeString(packet.response)
        }

        override fun read(input: ByteBuf): ClientboundStatusResponsePacket {
            return ClientboundStatusResponsePacket("")
        }
    }
}
