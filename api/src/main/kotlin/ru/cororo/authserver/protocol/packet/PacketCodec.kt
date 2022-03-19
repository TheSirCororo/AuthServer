package ru.cororo.authserver.protocol.packet

import io.ktor.utils.io.core.*

/**
 * Codec for packets. It is used to encode to bytes and decode from bytes packets
 */
interface PacketCodec<T : Packet> {
    val packetClass: Class<T>

    fun write(output: Output, packet: T)

    fun read(input: Input): T
}