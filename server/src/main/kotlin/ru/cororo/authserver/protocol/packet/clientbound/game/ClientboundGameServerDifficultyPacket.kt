package ru.cororo.authserver.protocol.packet.clientbound.game

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.world.Difficulty

data class ClientboundGameServerDifficultyPacket(
    val difficulty: Difficulty,
    val isLocked: Boolean = true
) : Packet {
    override val bound = PacketBound.CLIENT

    @OptIn(ExperimentalUnsignedTypes::class)
    companion object : PacketCodec<ClientboundGameServerDifficultyPacket> {
        override val packetClass = ClientboundGameServerDifficultyPacket::class.java

        override fun write(output: ByteBuf, packet: ClientboundGameServerDifficultyPacket) {
            output.writeByte(packet.difficulty.ordinal)
            output.writeBoolean(packet.isLocked)
        }

        override fun read(input: ByteBuf): ClientboundGameServerDifficultyPacket {
            return ClientboundGameServerDifficultyPacket(Difficulty.PEACEFUL, true)
        }

    }
}