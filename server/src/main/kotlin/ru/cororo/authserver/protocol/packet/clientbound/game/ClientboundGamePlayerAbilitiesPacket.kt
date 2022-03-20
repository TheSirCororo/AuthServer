package ru.cororo.authserver.protocol.packet.clientbound.game

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec

data class ClientboundGamePlayerAbilitiesPacket(
    val flags: Byte,
    val flyingSpeed: Float,
    val viewModifier: Float
) : Packet {
    override val bound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundGamePlayerAbilitiesPacket> {
        override val packetClass = ClientboundGamePlayerAbilitiesPacket::class.java

        override fun write(output: Output, packet: ClientboundGamePlayerAbilitiesPacket) {
            output.writeByte(packet.flags)
            output.writeFloat(packet.flyingSpeed)
            output.writeFloat(packet.viewModifier)
        }

        override fun read(input: Input): ClientboundGamePlayerAbilitiesPacket {
            return ClientboundGamePlayerAbilitiesPacket(0, 0.0f, 0.0f)
        }
    }
}