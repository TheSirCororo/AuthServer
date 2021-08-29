package ru.cororo.authserver.protocol.packet.serverbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec

data class ServerboundStatusPingPacket(
    val payload: Long
) {
    companion object : MinecraftPacketCodec<ServerboundStatusPingPacket> {
        override val packetClass = ServerboundStatusPingPacket::class.java

        override fun write(output: Output, packet: ServerboundStatusPingPacket) {}

        override fun read(input: Input): ServerboundStatusPingPacket {
            return ServerboundStatusPingPacket(input.readLong())
        }
    }
}