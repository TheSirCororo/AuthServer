package ru.cororo.authserver.protocol.packet.serverbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec

object ServerboundStatusRequestPacket : MinecraftPacketCodec<ServerboundStatusRequestPacket> {
    override fun toString() = "ServerboundStatusRequestPacket"

    override val packetClass = this.javaClass

    override fun write(output: Output, packet: ServerboundStatusRequestPacket) {}

    override fun read(input: Input) = ServerboundStatusRequestPacket
}