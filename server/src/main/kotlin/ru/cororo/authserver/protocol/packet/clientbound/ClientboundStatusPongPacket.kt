package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec

data class ClientboundStatusPongPacket(
    val payload: Long
) : Packet {
    override val bound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundStatusPongPacket> {
        override val packetClass = ClientboundStatusPongPacket::class.java

        override fun write(output: Output, packet: ClientboundStatusPongPacket) {
            output.writeLong(packet.payload)
        }

        override fun read(input: Input): ClientboundStatusPongPacket {
            return ClientboundStatusPongPacket(0L)
        }
    }
}