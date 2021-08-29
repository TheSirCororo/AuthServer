package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.writeUUID
import java.util.*

data class ClientboundLoginSuccessPacket(
    val uuid: UUID,
    val username: String
) {
    companion object : MinecraftPacketCodec<ClientboundLoginSuccessPacket> {
        override val packetClass = ClientboundLoginSuccessPacket::class.java

        override fun write(output: Output, packet: ClientboundLoginSuccessPacket) {
            output.writeUUID(packet.uuid)
        }

        override fun read(input: Input): ClientboundLoginSuccessPacket {
            return ClientboundLoginSuccessPacket(UUID.randomUUID(), "")
        }
    }
}