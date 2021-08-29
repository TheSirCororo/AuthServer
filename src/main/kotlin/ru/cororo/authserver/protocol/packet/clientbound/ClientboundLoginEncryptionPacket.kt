package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.writeString

data class ClientboundLoginEncryptionPacket(
    val serverId: String,
    val publicKey: String,
    val verifyToken: String
) {
    companion object : MinecraftPacketCodec<ClientboundLoginEncryptionPacket> {
        override val packetClass = ClientboundLoginEncryptionPacket::class.java

        override fun write(output: Output, packet: ClientboundLoginEncryptionPacket) {
            output.apply {
                writeString(packet.serverId)
                writeString(packet.publicKey)
                writeString(packet.verifyToken)
            }
        }

        override fun read(input: Input): ClientboundLoginEncryptionPacket {
            return ClientboundLoginEncryptionPacket("", "", "")
        }
    }
}