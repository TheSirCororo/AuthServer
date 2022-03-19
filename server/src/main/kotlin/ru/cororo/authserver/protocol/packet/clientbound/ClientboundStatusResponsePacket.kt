package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.utils.writeString

data class ClientboundStatusResponsePacket(
    val response: String
) : Packet {
    override val bound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundStatusResponsePacket> {
        override val packetClass = ClientboundStatusResponsePacket::class.java

        override fun write(output: Output, packet: ClientboundStatusResponsePacket) {
            output.writeString(packet.response)
        }

        override fun read(input: Input): ClientboundStatusResponsePacket {
            return ClientboundStatusResponsePacket("")
        }
    }
}
