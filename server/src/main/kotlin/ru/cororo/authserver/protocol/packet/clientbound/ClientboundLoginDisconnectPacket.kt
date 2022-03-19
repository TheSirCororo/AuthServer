package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.utils.readString
import ru.cororo.authserver.protocol.utils.writeString

data class ClientboundLoginDisconnectPacket(
    val reason: String
) : Packet {
    override val bound: PacketBound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundLoginDisconnectPacket> {
        override val packetClass = ClientboundLoginDisconnectPacket::class.java

        override fun read(input: Input): ClientboundLoginDisconnectPacket {
            return ClientboundLoginDisconnectPacket(input.readString())
        }

        override fun write(output: Output, packet: ClientboundLoginDisconnectPacket) {
            output.writeString(packet.reason)
        }
    }
}