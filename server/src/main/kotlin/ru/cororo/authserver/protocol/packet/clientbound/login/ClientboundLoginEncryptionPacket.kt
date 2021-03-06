package ru.cororo.authserver.protocol.packet.clientbound.login

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.writeByteArray
import ru.cororo.authserver.util.writeString

data class ClientboundLoginEncryptionPacket(
    val serverId: String,
    val publicKey: ByteArray,
    val verifyToken: ByteArray
) : Packet {
    override val bound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundLoginEncryptionPacket> {
        override val packetClass = ClientboundLoginEncryptionPacket::class.java

        override fun write(output: ByteBuf, packet: ClientboundLoginEncryptionPacket) {
            output.apply {
                writeString(packet.serverId)
                writeByteArray(packet.publicKey)
                writeByteArray(packet.verifyToken)
            }
        }

        override fun read(input: ByteBuf): ClientboundLoginEncryptionPacket {
            return ClientboundLoginEncryptionPacket("", byteArrayOf(), byteArrayOf())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ClientboundLoginEncryptionPacket

        if (serverId != other.serverId) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (!verifyToken.contentEquals(other.verifyToken)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = serverId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + verifyToken.contentHashCode()
        return result
    }
}