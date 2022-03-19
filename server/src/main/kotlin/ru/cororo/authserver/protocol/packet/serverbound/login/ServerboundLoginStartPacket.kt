package ru.cororo.authserver.protocol.packet.serverbound.login

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.utils.readString

data class ServerboundLoginStartPacket(
    val username: String
) : Packet {
    override val bound = PacketBound.SERVER

    companion object : PacketCodec<ServerboundLoginStartPacket> {
        override val packetClass = ServerboundLoginStartPacket::class.java

        override fun write(output: Output, packet: ServerboundLoginStartPacket) {}

        override fun read(input: Input) =
            ServerboundLoginStartPacket(input.readString())
    }
}