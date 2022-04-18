package ru.cororo.authserver.protocol.packet.clientbound.game

import io.ktor.utils.io.core.*
import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.writeByteArray
import ru.cororo.authserver.util.writeString

data class ClientboundGamePluginMessagePacket(
    val identifier: String,
    val data: ByteArray
) : Packet {
    override val bound = PacketBound.CLIENT

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ClientboundGamePluginMessagePacket

        if (identifier != other.identifier) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identifier.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object : PacketCodec<ClientboundGamePluginMessagePacket> {
        override val packetClass = ClientboundGamePluginMessagePacket::class.java

        override fun write(output: ByteBuf, packet: ClientboundGamePluginMessagePacket) {
            output.writeString(packet.identifier)
            output.writeByteArray(packet.data)
        }

        override fun read(input: ByteBuf): ClientboundGamePluginMessagePacket {
            throw IllegalStateException()
        }

    }
}