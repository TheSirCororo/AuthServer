package ru.cororo.authserver.protocol.packet.clientbound.game

import io.ktor.utils.io.core.*
import kotlinx.serialization.encodeToString
import net.benwoodworth.knbt.NbtCompound
import net.benwoodworth.knbt.StringifiedNbt
import net.benwoodworth.knbt.nbtString
import net.kyori.adventure.nbt.CompoundBinaryTag
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.util.*

data class ClientboundGameJoinPacket117(
    val entityId: Int,
    val hardcore: Boolean,
    val gameMode: Byte,
    val previousGameMode: Byte,
    val worldNames: Array<String>,
    val dimensionCodec: CompoundBinaryTag,
    val dimension: CompoundBinaryTag,
    val worldName: String,
    val hashedSeed: Long,
    val maxPlayers: Int,
    val viewDistance: Int,
    val debugInfo: Boolean,
    val respawnScreen: Boolean,
    val debug: Boolean,
    val flat: Boolean
) : Packet {
    override val bound = PacketBound.CLIENT

    @ExperimentalUnsignedTypes
    companion object : PacketCodec<ClientboundGameJoinPacket> {
        override val packetClass = ClientboundGameJoinPacket::class.java

        override fun write(output: Output, packet: ClientboundGameJoinPacket) {
            output.writeInt(packet.entityId)
            output.writeBoolean(packet.hardcore)
            output.writeUByte(packet.gameMode.toUByte())
            output.writeByte((packet.gameMode - 1).toByte())
            output.writeStringArray(arrayOf("minecraft:world"))

            output.writeNBT(packet.dimensionCodec)
            output.writeNBT(packet.dimension)

            output.writeString(packet.worldName)
            output.writeLong(packet.hashedSeed)
            output.writeVarInt(packet.maxPlayers)
            output.writeVarInt(packet.viewDistance)
            output.writeBoolean(packet.debugInfo)
            output.writeBoolean(packet.respawnScreen)
            output.writeBoolean(packet.debug)
            output.writeBoolean(packet.flat)
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

    override fun toString(): String {
        return "ClientboundGameJoinPacket(entityId=$entityId, hardcore=$hardcore, gameMode=$gameMode, previousGameMode=$previousGameMode, worldNames=${worldNames.contentToString()}, dimensionCodec=${StringifiedNbt {}.encodeToString(dimensionCodec)}, dimension=$dimension, worldName='$worldName', hashedSeed=$hashedSeed, maxPlayers=$maxPlayers, viewDistance=$viewDistance, debugInfo=$debugInfo, respawnScreen=$respawnScreen, debug=$debug, flat=$flat)"
    }
}