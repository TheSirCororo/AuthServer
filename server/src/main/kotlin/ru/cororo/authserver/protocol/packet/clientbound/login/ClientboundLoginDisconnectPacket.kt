package ru.cororo.authserver.protocol.packet.clientbound.login

import io.netty.buffer.ByteBuf
import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.readComponent
import ru.cororo.authserver.util.writeComponent

data class ClientboundLoginDisconnectPacket(
    val reason: Component
) : Packet {
    override val bound: PacketBound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundLoginDisconnectPacket> {
        override val packetClass = ClientboundLoginDisconnectPacket::class.java

        override fun read(input: ByteBuf): ClientboundLoginDisconnectPacket {
            return ClientboundLoginDisconnectPacket(input.readComponent())
        }

        override fun write(output: ByteBuf, packet: ClientboundLoginDisconnectPacket) {
            output.writeComponent(packet.reason)
        }
    }
}