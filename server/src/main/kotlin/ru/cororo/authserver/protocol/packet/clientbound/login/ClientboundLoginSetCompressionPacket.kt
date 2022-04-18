package ru.cororo.authserver.protocol.packet.clientbound.login

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.writeVarInt

data class ClientboundLoginSetCompressionPacket(
    val maxCompressedSize: Int
) : Packet {
    override val bound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundLoginSetCompressionPacket> {
        override val packetClass = ClientboundLoginSetCompressionPacket::class.java

        override fun write(output: ByteBuf, packet: ClientboundLoginSetCompressionPacket) {
            output.writeVarInt(packet.maxCompressedSize)
        }

        override fun read(input: ByteBuf): ClientboundLoginSetCompressionPacket {
            return ClientboundLoginSetCompressionPacket(0)
        }
    }

}