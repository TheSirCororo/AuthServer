package ru.cororo.authserver.protocol.packet.clientbound.game

import io.netty.buffer.ByteBuf
import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.readComponent
import ru.cororo.authserver.util.writeComponent

data class ClientboundGameDisconnectPacket(
    val reason: Component
) : Packet {
    override val bound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundGameDisconnectPacket> {
        override val packetClass = ClientboundGameDisconnectPacket::class.java

        override fun write(output: ByteBuf, packet: ClientboundGameDisconnectPacket) {
            output.writeComponent(packet.reason)
        }

        override fun read(input: ByteBuf): ClientboundGameDisconnectPacket {
            return ClientboundGameDisconnectPacket(input.readComponent())
        }
    }
}