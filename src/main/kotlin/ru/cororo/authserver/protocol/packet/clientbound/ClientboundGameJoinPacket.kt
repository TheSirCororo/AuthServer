package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.writeBoolean
import ru.cororo.authserver.protocol.utils.writeString
import ru.cororo.authserver.protocol.utils.writeStringArray
import ru.cororo.authserver.protocol.utils.writeVarInt

data class ClientboundGameJoinPacket(
    val entityId: Int,
    val hardcore: Boolean,
    val gameMode: Byte,
    val previousGameMode: Byte,
    val worldCount: Int,
    val worldNames: Array<String>,
    val dimensionCodec: String,
    val dimension: String,
    val worldName: String,
    val hashedSeed: Long,
    val maxPlayers: Int,
    val viewDistance: Int,
    val debugInfo: Boolean,
    val respawnScreen: Boolean,
    val debug: Boolean,
    val flat: Boolean
) {
    companion object : MinecraftPacketCodec<ClientboundGameJoinPacket> {
        override val packetClass = ClientboundGameJoinPacket::class.java

        override fun write(output: Output, packet: ClientboundGameJoinPacket) {
            output.apply {
                writeInt(packet.entityId)
                writeBoolean(packet.hardcore)
                writeByte(packet.gameMode)
                writeByte(packet.previousGameMode)
                writeVarInt(packet.worldCount)
                writeStringArray(packet.worldNames)
                writeString(packet.dimensionCodec)
                writeString(packet.worldName)
                writeLong(packet.hashedSeed)
                writeVarInt(packet.maxPlayers)
                writeVarInt(packet.viewDistance)
                writeBoolean(packet.debugInfo)
                writeBoolean(packet.respawnScreen)
                writeBoolean(packet.debug)
                writeBoolean(packet.flat)
            }
        }

        override fun read(input: Input): ClientboundGameJoinPacket {
            throw UnsupportedOperationException()
        }
    }
}