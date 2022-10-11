package ru.cororo.authserver.protocol.packet.serverbound.handshake

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.readString
import ru.cororo.authserver.util.readVarInt

data class ServerboundHandshakePacket(
    val protocolVersion: Int = 0,
    val serverAddress: String = "",
    val port: UShort = 0U,
    val nextState: Int = 0
) : Packet {
    override val bound = PacketBound.SERVER

    companion object : PacketCodec<ServerboundHandshakePacket> {
        override fun write(output: ByteBuf, packet: ServerboundHandshakePacket) {}

        override fun read(input: ByteBuf): ServerboundHandshakePacket {
            val protocolVersion = input.readVarInt()
            val serverAddress = input.readString(255)
            val port = input.readShort()
            val nextState = input.readVarInt()
            return ServerboundHandshakePacket(protocolVersion, serverAddress, port.toUShort(), nextState)
        }

        override val packetClass = ServerboundHandshakePacket::class.java
    }
}