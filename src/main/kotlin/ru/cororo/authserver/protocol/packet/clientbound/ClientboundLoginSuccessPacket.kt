package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.readString
import ru.cororo.authserver.protocol.utils.writeString
import ru.cororo.authserver.protocol.utils.writeUUID
import java.math.BigInteger
import java.util.*

data class ClientboundLoginSuccessPacket(
    val uuid: UUID,
    val username: String
) {
    companion object : MinecraftPacketCodec<ClientboundLoginSuccessPacket> {
        override val packetClass = ClientboundLoginSuccessPacket::class.java

        override fun write(output: Output, packet: ClientboundLoginSuccessPacket) {
            output.writeUUID(packet.uuid)
            output.writeString(packet.username)
        }

        override fun read(input: Input): ClientboundLoginSuccessPacket {
            return ClientboundLoginSuccessPacket(UUID.randomUUID(), input.readString())
        }
    }
}