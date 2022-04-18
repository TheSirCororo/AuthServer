package ru.cororo.authserver.protocol.packet.serverbound.status

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec

object ServerboundStatusRequestPacket : Packet, PacketCodec<ServerboundStatusRequestPacket> {
    override val bound = PacketBound.SERVER

    override fun toString() = "ServerboundStatusRequestPacket"

    override val packetClass = javaClass

    override fun write(output: ByteBuf, packet: ServerboundStatusRequestPacket) {}

    override fun read(input: ByteBuf) = ServerboundStatusRequestPacket
}