package ru.cororo.authserver.protocol.packet.clientbound.game

import io.netty.buffer.ByteBuf
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

        override fun write(output: ByteBuf, packet: ClientboundGamePlayerAbilitiesPacket) {
            output.writeByte(packet.flags.toInt())
            output.writeFloat(packet.flyingSpeed)
            output.writeFloat(packet.viewModifier)
        }

        override fun read(input: ByteBuf): ClientboundGamePlayerAbilitiesPacket {
            return ClientboundGamePlayerAbilitiesPacket(0, 0.0f, 0.0f)
        }
    }
}