package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.writeString

data class ClientboundLoginDisconnectPacket(
    val reason: String
) {
    companion object : MinecraftPacketCodec<ClientboundLoginDisconnectPacket> {
        override val packetClass = ClientboundLoginDisconnectPacket::class.java

        override fun read(input: Input): ClientboundLoginDisconnectPacket {
            return ClientboundLoginDisconnectPacket("{}")
        }

        override fun write(output: Output, packet: ClientboundLoginDisconnectPacket) {
            output.writeString(packet.reason)
        }
    }
}