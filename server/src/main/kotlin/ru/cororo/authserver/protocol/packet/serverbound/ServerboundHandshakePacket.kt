package ru.cororo.authserver.protocol.packet.serverbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.utils.*

data class ServerboundHandshakePacket(
    val protocolVersion: Int = 0,
    val serverAddress: String = "",
    val port: UShort = 0U,
    val nextState: Int = 0
) : Packet {
    override val bound = PacketBound.SERVER

    @ExperimentalUnsignedTypes
    companion object : PacketCodec<ServerboundHandshakePacket> {
        override fun write(output: Output, packet: ServerboundHandshakePacket) {}

        override fun read(input: Input): ServerboundHandshakePacket {
            val protocolVersion = input.readVarInt()
            val serverAddress = input.readString()
            val port = input.readUShort()
            val nextState = input.readVarInt()
            return ServerboundHandshakePacket(protocolVersion, serverAddress, port, nextState)
        }

        override val packetClass = ServerboundHandshakePacket::class.java
    }
}