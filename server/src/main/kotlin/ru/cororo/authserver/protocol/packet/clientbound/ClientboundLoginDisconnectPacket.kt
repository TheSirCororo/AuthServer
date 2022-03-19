package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.utils.readComponent
import ru.cororo.authserver.protocol.utils.readString
import ru.cororo.authserver.protocol.utils.writeComponent
import ru.cororo.authserver.protocol.utils.writeString

data class ClientboundLoginDisconnectPacket(
    val reason: Component
) : Packet {
    override val bound: PacketBound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundLoginDisconnectPacket> {
        override val packetClass = ClientboundLoginDisconnectPacket::class.java

        override fun read(input: Input): ClientboundLoginDisconnectPacket {
            return ClientboundLoginDisconnectPacket(input.readComponent())
        }

        override fun write(output: Output, packet: ClientboundLoginDisconnectPacket) {
            output.writeComponent(packet.reason)
        }
    }
}