package ru.cororo.authserver.protocol.packet.serverbound.login

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.readString

data class ServerboundLoginStartPacket(
    val username: String
) : Packet {
    override val bound = PacketBound.SERVER

    companion object : PacketCodec<ServerboundLoginStartPacket> {
        override val packetClass = ServerboundLoginStartPacket::class.java

        override fun write(output: ByteBuf, packet: ServerboundLoginStartPacket) {}

        override fun read(input: ByteBuf) =
            ServerboundLoginStartPacket(input.readString())
    }
}