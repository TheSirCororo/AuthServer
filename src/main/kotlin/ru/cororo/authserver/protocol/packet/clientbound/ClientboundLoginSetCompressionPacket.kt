package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.writeVarInt

data class ClientboundLoginSetCompressionPacket(
    val maxCompressedSize: Int
) {
    companion object : MinecraftPacketCodec<ClientboundLoginSetCompressionPacket> {
        override val packetClass = ClientboundLoginSetCompressionPacket::class.java

        override fun write(output: Output, packet: ClientboundLoginSetCompressionPacket) {
            output.writeVarInt(packet.maxCompressedSize)
        }

        override fun read(input: Input): ClientboundLoginSetCompressionPacket {
            return ClientboundLoginSetCompressionPacket(0)
        }
    }

}