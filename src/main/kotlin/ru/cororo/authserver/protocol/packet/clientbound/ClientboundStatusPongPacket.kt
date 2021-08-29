package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.writeVarInt

data class ClientboundStatusPongPacket(
    val payload: Long
) {
    companion object : MinecraftPacketCodec<ClientboundStatusPongPacket> {
        override val packetClass = ClientboundStatusPongPacket::class.java

        override fun write(output: Output, packet: ClientboundStatusPongPacket) {
            output.writeVarInt(packet.payload.toInt())
        }

        override fun read(input: Input): ClientboundStatusPongPacket {
            return ClientboundStatusPongPacket(input.readLong())
        }
    }
}