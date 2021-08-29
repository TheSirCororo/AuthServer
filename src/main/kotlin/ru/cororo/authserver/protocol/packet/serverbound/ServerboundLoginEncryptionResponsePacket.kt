package ru.cororo.authserver.protocol.packet.serverbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.readString

data class ServerboundLoginEncryptionResponsePacket(
    val secret: String,
    val verifyToken: String
) {
    companion object : MinecraftPacketCodec<ServerboundLoginEncryptionResponsePacket> {
        override val packetClass = ServerboundLoginEncryptionResponsePacket::class.java

        override fun write(output: Output, packet: ServerboundLoginEncryptionResponsePacket) {}

        override fun read(input: Input): ServerboundLoginEncryptionResponsePacket {
            val secret = input.readString()
            val verifyToken = input.readString()
            return ServerboundLoginEncryptionResponsePacket(secret, verifyToken)
        }
    }
}