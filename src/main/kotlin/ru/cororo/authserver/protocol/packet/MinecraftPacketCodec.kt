package ru.cororo.authserver.protocol.packet

import io.ktor.utils.io.core.*

interface MinecraftPacketCodec<out T> {
    val packetClass: Class<out T>

    fun write(output: Output, packet: @UnsafeVariance T)

    fun read(input: Input): T
}