package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.writeByteArray
import ru.cororo.authserver.protocol.utils.writeString

data class ClientboundGamePluginMessagePacket(
    val identifier: String,
    val data: ByteArray
) {
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

    companion object : MinecraftPacketCodec<ClientboundGamePluginMessagePacket> {
        override val packetClass = ClientboundGamePluginMessagePacket::class.java

        override fun write(output: Output, packet: ClientboundGamePluginMessagePacket) {
            output.writeString(packet.identifier)
            output.writeByteArray(packet.data)
        }

        override fun read(input: Input): ClientboundGamePluginMessagePacket {
            throw IllegalStateException()
        }

    }
}