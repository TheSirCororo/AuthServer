package ru.cororo.authserver.protocol.packet

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DecoderException
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ServerboundPacket
import ru.cororo.authserver.protocol.buffer.readString
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeString
import ru.cororo.authserver.protocol.buffer.writeVarInt

/**
 * First packet of every connection. [serverAddress] is raw: BungeeCord-style forwarding appends
 * `\0`-separated player data to it, so it may be far longer than a host name.
 */
data class HandshakePacket(
    val protocolVersion: Int,
    val serverAddress: String,
    val serverPort: Int,
    val intent: Intent,
) : ServerboundPacket {
    enum class Intent(val id: Int) {
        STATUS(1),
        LOGIN(2),
        /** Login after a transfer packet, since 1.20.5. */
        TRANSFER(3);

        companion object {
            fun byId(id: Int): Intent = entries.firstOrNull { it.id == id } ?: throw DecoderException("Unknown intent $id")
        }
    }

    companion object Codec : PacketCodec<HandshakePacket> {
        override fun encode(buffer: ByteBuf, packet: HandshakePacket, version: ProtocolVersion) {
            buffer.writeVarInt(packet.protocolVersion)
            buffer.writeString(packet.serverAddress)
            buffer.writeShort(packet.serverPort)
            buffer.writeVarInt(packet.intent.id)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = HandshakePacket(
            protocolVersion = buffer.readVarInt(),
            serverAddress = buffer.readString(),
            serverPort = buffer.readUnsignedShort(),
            intent = Intent.byId(buffer.readVarInt()),
        )
    }
}
