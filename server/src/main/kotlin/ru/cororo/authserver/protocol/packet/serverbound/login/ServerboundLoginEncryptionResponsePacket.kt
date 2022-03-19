package ru.cororo.authserver.protocol.packet.serverbound.login

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.utils.readByteArray

data class ServerboundLoginEncryptionResponsePacket(
    val secret: ByteArray,
    val verifyToken: ByteArray
) : Packet {
    override val bound = PacketBound.SERVER

    companion object : PacketCodec<ServerboundLoginEncryptionResponsePacket> {
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