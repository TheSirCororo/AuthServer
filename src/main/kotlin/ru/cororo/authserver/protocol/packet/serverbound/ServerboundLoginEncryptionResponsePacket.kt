package ru.cororo.authserver.protocol.packet.serverbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.readByteArray

data class ServerboundLoginEncryptionResponsePacket(
    val secret: ByteArray,
    val verifyToken: ByteArray
) {
    companion object : MinecraftPacketCodec<ServerboundLoginEncryptionResponsePacket> {
        override val packetClass = ServerboundLoginEncryptionResponsePacket::class.java

        override fun write(output: Output, packet: ServerboundLoginEncryptionResponsePacket) {}

        override fun read(input: Input): ServerboundLoginEncryptionResponsePacket {
            val secret = input.readByteArray()
            val verifyToken = input.readByteArray()
            return ServerboundLoginEncryptionResponsePacket(secret, verifyToken)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ServerboundLoginEncryptionResponsePacket

        if (!secret.contentEquals(other.secret)) return false
        if (!verifyToken.contentEquals(other.verifyToken)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = secret.contentHashCode()
        result = 31 * result + verifyToken.contentHashCode()
        return result
    }
}