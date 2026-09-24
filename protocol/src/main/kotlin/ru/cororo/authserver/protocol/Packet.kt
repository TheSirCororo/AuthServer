package ru.cororo.authserver.protocol

import io.netty.buffer.ByteBuf

/** Connection phase; each phase has its own packet ID space. */
enum class ProtocolState {
    HANDSHAKE,
    STATUS,
    LOGIN,
    /** Exists since 1.20.2. */
    CONFIGURATION,
    PLAY;

    val key: String = name.lowercase()
}

enum class PacketDirection {
    /** Server to client. */
    CLIENTBOUND,
    /** Client to server. */
    SERVERBOUND;

    val key: String = name.lowercase()
}

/** A version-independent packet model. Wire layouts live in the matching [PacketCodec]. */
interface Packet

/** Packets sent by the server. */
interface ClientboundPacket : Packet

/** Packets sent by the client. */
interface ServerboundPacket : Packet

/**
 * Converts a packet between its model and the wire layout used by a particular [ProtocolVersion].
 * The packet ID is handled by the registry, codecs only see the body.
 */
interface PacketCodec<T : Packet> {
    fun encode(buffer: ByteBuf, packet: T, version: ProtocolVersion)

    fun decode(buffer: ByteBuf, version: ProtocolVersion): T
}

/** Codec for packets the server only sends; decoding exists only in tests and tools. */
interface EncodeOnlyCodec<T : Packet> : PacketCodec<T> {
    override fun decode(buffer: ByteBuf, version: ProtocolVersion): T =
        throw UnsupportedOperationException("${javaClass.simpleName} is encode-only")
}

/** Codec for packets without a body. */
class EmptyCodec<T : Packet>(private val instance: T) : PacketCodec<T> {
    override fun encode(buffer: ByteBuf, packet: T, version: ProtocolVersion) = Unit

    override fun decode(buffer: ByteBuf, version: ProtocolVersion): T = instance
}
