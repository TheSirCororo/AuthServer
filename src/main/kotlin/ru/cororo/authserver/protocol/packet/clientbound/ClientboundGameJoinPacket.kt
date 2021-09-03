package ru.cororo.authserver.protocol.packet.clientbound

import io.ktor.utils.io.core.*
import net.benwoodworth.knbt.NbtCompound
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.utils.*

data class ClientboundGameJoinPacket(
    val entityId: Int,
    val hardcore: Boolean,
    val gameMode: Byte,
    val previousGameMode: Byte,
    val worldNames: Array<String>,
    val dimensionCodec: NbtCompound,
    val dimension: NbtCompound,
    val worldName: String,
    val hashedSeed: Long,
    val maxPlayers: Int,
    val viewDistance: Int,
    val debugInfo: Boolean,
    val respawnScreen: Boolean,
    val debug: Boolean,
    val flat: Boolean
) {
    @ExperimentalUnsignedTypes
    companion object : MinecraftPacketCodec<ClientboundGameJoinPacket> {
        override val packetClass = ClientboundGameJoinPacket::class.java

        override fun write(output: Output, packet: ClientboundGameJoinPacket) {
            output.apply {
                writeInt(packet.entityId)
                writeBoolean(packet.hardcore)
                writeByte(packet.gameMode)
                writeByte(packet.previousGameMode)
                writeStringArray(packet.worldNames)
                writeNBT(packet.dimensionCodec, "")
                writeNBT(packet.dimension, "")
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ClientboundGameJoinPacket

        if (entityId != other.entityId) return false
        if (hardcore != other.hardcore) return false
        if (gameMode != other.gameMode) return false
        if (previousGameMode != other.previousGameMode) return false
        if (!worldNames.contentEquals(other.worldNames)) return false
        if (dimensionCodec != other.dimensionCodec) return false
        if (dimension != other.dimension) return false
        if (worldName != other.worldName) return false
        if (hashedSeed != other.hashedSeed) return false
        if (maxPlayers != other.maxPlayers) return false
        if (viewDistance != other.viewDistance) return false
        if (debugInfo != other.debugInfo) return false
        if (respawnScreen != other.respawnScreen) return false
        if (debug != other.debug) return false
        if (flat != other.flat) return false

        return true
    }

    override fun hashCode(): Int {
        var result = entityId
        result = 31 * result + hardcore.hashCode()
        result = 31 * result + gameMode
        result = 31 * result + previousGameMode
        result = 31 * result + worldNames.contentHashCode()
        result = 31 * result + dimensionCodec.hashCode()
        result = 31 * result + dimension.hashCode()
        result = 31 * result + worldName.hashCode()
        result = 31 * result + hashedSeed.hashCode()
        result = 31 * result + maxPlayers
        result = 31 * result + viewDistance
        result = 31 * result + debugInfo.hashCode()
        result = 31 * result + respawnScreen.hashCode()
        result = 31 * result + debug.hashCode()
        result = 31 * result + flat.hashCode()
        return result
    }
}