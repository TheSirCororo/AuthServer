package ru.cororo.authserver.protocol.packet.clientbound.game

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.util.writeBoolean
import ru.cororo.authserver.world.Difficulty

data class ClientboundGameServerDifficultyPacket(
    val difficulty: Difficulty,
    val isLocked: Boolean = true
) : Packet {
    override val bound = PacketBound.CLIENT

    @OptIn(ExperimentalUnsignedTypes::class)
    companion object : PacketCodec<ClientboundGameServerDifficultyPacket> {
        override val packetClass = ClientboundGameServerDifficultyPacket::class.java

        override fun write(output: Output, packet: ClientboundGameServerDifficultyPacket) {
            output.writeUByte(packet.difficulty.ordinal.toUByte())
            output.writeBoolean(packet.isLocked)
        }

        override fun read(input: Input): ClientboundGameServerDifficultyPacket {
            return ClientboundGameServerDifficultyPacket(Difficulty.PEACEFUL, true)
        }

    }
}