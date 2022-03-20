package ru.cororo.authserver.protocol.packet.clientbound.game

import io.ktor.utils.io.core.*
import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.util.readComponent
import ru.cororo.authserver.protocol.util.writeComponent

data class ClientboundGameDisconnectPacket(
    val reason: Component
) : Packet {
    override val bound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundGameDisconnectPacket> {
        override val packetClass = ClientboundGameDisconnectPacket::class.java

        override fun write(output: Output, packet: ClientboundGameDisconnectPacket) {
            output.writeComponent(packet.reason)
        }

        override fun read(input: Input): ClientboundGameDisconnectPacket {
            return ClientboundGameDisconnectPacket(input.readComponent())
        }
    }
}