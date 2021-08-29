package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.readString
import ru.cororo.authserver.protocol.utils.writeString

data class ClientboundStatusResponsePacket(
    val response: String
) {
    companion object : MinecraftPacketCodec<ClientboundStatusResponsePacket> {
        override val packetClass = ClientboundStatusResponsePacket::class.java

        override fun write(output: Output, packet: ClientboundStatusResponsePacket) {
            output.writeString(packet.response)
        }

        override fun read(input: Input): ClientboundStatusResponsePacket {
            return ClientboundStatusResponsePacket("")
        }
    }
}