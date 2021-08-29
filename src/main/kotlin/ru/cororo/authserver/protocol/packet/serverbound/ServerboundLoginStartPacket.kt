package ru.cororo.authserver.protocol.packet.serverbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.readString
import ru.cororo.authserver.protocol.utils.writeString

data class ServerboundLoginStartPacket(
    val username: String
) {
    companion object : MinecraftPacketCodec<ServerboundLoginStartPacket> {
        override val packetClass = ServerboundLoginStartPacket::class.java

        override fun write(output: Output, packet: ServerboundLoginStartPacket) {}

        override fun read(input: Input) =
            ServerboundLoginStartPacket(input.readString())
    }
}