package ru.cororo.authserver.protocol.packet

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.EmptyCodec
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ServerboundPacket
import ru.cororo.authserver.protocol.buffer.readIdentifier
import ru.cororo.authserver.protocol.buffer.readList
import ru.cororo.authserver.protocol.buffer.readRemainingBytes
import ru.cororo.authserver.protocol.buffer.readString
import ru.cororo.authserver.protocol.buffer.writeIdentifier
import ru.cororo.authserver.protocol.buffer.writeList
import ru.cororo.authserver.protocol.buffer.writeString

// Configuration state, 1.20.2+.

/**
 * A pre-encoded packet body whose layout is owned by game data rather than by this module
 * (registry data, tags). Bodies are produced for one exact protocol version.
 */
abstract class RawBodyPacket(val body: ByteArray) : ClientboundPacket

class RegistryDataPacket(body: ByteArray) : RawBodyPacket(body) {
    companion object Codec : RawBodyCodec<RegistryDataPacket>(::RegistryDataPacket)
}

/** `Update Tags`; also sent in the play state before 1.20.2. */
class UpdateTagsPacket(body: ByteArray) : RawBodyPacket(body) {
    companion object Codec : RawBodyCodec<UpdateTagsPacket>(::UpdateTagsPacket)
}

open class RawBodyCodec<T : RawBodyPacket>(private val factory: (ByteArray) -> T) : PacketCodec<T> {
    override fun encode(buffer: ByteBuf, packet: T, version: ProtocolVersion) {
        buffer.writeBytes(packet.body)
    }

    override fun decode(buffer: ByteBuf, version: ProtocolVersion): T = factory(buffer.readRemainingBytes())
}

data class FeatureFlagsPacket(val features: List<String>) : ClientboundPacket {
    companion object Codec : PacketCodec<FeatureFlagsPacket> {
        override fun encode(buffer: ByteBuf, packet: FeatureFlagsPacket, version: ProtocolVersion) =
            buffer.writeList(packet.features) { writeIdentifier(it) }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            FeatureFlagsPacket(buffer.readList(64) { readIdentifier() })
    }
}

data class KnownPack(val namespace: String, val id: String, val version: String)

private fun ByteBuf.writeKnownPacks(packs: List<KnownPack>) = writeList(packs) {
    writeString(it.namespace)
    writeString(it.id)
    writeString(it.version)
}

private fun ByteBuf.readKnownPacks(): List<KnownPack> = readList(64) { KnownPack(readString(), readString(), readString()) }

/** Data packs the server can reference by ID instead of sending registry contents (1.20.5+). */
data class ClientboundKnownPacksPacket(val packs: List<KnownPack>) : ClientboundPacket {
    companion object Codec : PacketCodec<ClientboundKnownPacksPacket> {
        override fun encode(buffer: ByteBuf, packet: ClientboundKnownPacksPacket, version: ProtocolVersion) =
            buffer.writeKnownPacks(packet.packs)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = ClientboundKnownPacksPacket(buffer.readKnownPacks())
    }
}

data class ServerboundKnownPacksPacket(val packs: List<KnownPack>) : ServerboundPacket {
    companion object Codec : PacketCodec<ServerboundKnownPacksPacket> {
        override fun encode(buffer: ByteBuf, packet: ServerboundKnownPacksPacket, version: ProtocolVersion) =
            buffer.writeKnownPacks(packet.packs)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = ServerboundKnownPacksPacket(buffer.readKnownPacks())
    }
}

data object FinishConfigurationPacket : ClientboundPacket {
    val Codec = EmptyCodec(this)
}

/** Client acknowledgement of [FinishConfigurationPacket]; the client is in play state afterwards. */
data object FinishConfigurationAckPacket : ServerboundPacket {
    val Codec = EmptyCodec(this)
}

/** Client acknowledgement of a play-to-configuration switch. */
data object ConfigurationAcknowledgedPacket : ServerboundPacket {
    val Codec = EmptyCodec(this)
}
