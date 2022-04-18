package ru.cororo.authserver.protocol.packet

import io.netty.buffer.ByteBuf

/**
 * Codec for packets. It is used to encode to bytes and decode from bytes packets
 */
interface PacketCodec<T : Packet> {
    val packetClass: Class<T>

    fun write(output: ByteBuf, packet: T)

    fun read(input: ByteBuf): T
}