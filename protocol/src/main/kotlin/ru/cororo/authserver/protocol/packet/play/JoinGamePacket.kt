package ru.cororo.authserver.protocol.packet.play

import io.netty.buffer.ByteBuf
import net.kyori.adventure.nbt.CompoundBinaryTag
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_14
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_15
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_16
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_16_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_18
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_5
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_21_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_9_1
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_26_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_26_3
import ru.cororo.authserver.protocol.buffer.readBlockPosition
import ru.cororo.authserver.protocol.buffer.readCompound
import ru.cororo.authserver.protocol.buffer.readIdentifier
import ru.cororo.authserver.protocol.buffer.readList
import ru.cororo.authserver.protocol.buffer.readOptional
import ru.cororo.authserver.protocol.buffer.readString
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeCompound
import ru.cororo.authserver.protocol.buffer.writeIdentifier
import ru.cororo.authserver.protocol.buffer.writeList
import ru.cororo.authserver.protocol.buffer.writeString
import ru.cororo.authserver.protocol.buffer.writeVarInt

enum class GameMode(val id: Int) {
    SURVIVAL(0),
    CREATIVE(1),
    ADVENTURE(2),
    SPECTATOR(3);

    companion object {
        fun byId(id: Int): GameMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How the joined dimension is described; which fields are used depends on the client version.
 * Game data fills every field for the target version.
 */
data class DimensionInfo(
    /** Dimension type key, e.g. `minecraft:overworld`. */
    val type: String = "minecraft:overworld",
    /** Level (world) key. */
    val name: String = "minecraft:overworld",
    /** Whole registry codec sent inside the join packet (1.16 - 1.20.1). */
    val registryCodec: CompoundBinaryTag? = null,
    /** The dimension type element itself (1.16.2 - 1.18.2). */
    val typeElement: CompoundBinaryTag? = null,
    /** Network ID of [type] in the dimension type registry (1.20.5+). */
    val typeId: Int = 0,
    /** Numeric dimension before 1.16: -1 nether, 0 overworld, 1 end. */
    val legacyId: Int = 0,
)

data class JoinGamePacket(
    val entityId: Int,
    val gameMode: GameMode,
    val dimension: DimensionInfo,
    val hardcore: Boolean = false,
    val previousGameMode: GameMode? = null,
    val hashedSeed: Long = 0,
    val maxPlayers: Int = 1,
    val viewDistance: Int = 2,
    val simulationDistance: Int = 2,
    val reducedDebugInfo: Boolean = false,
    val enableRespawnScreen: Boolean = true,
    val doLimitedCrafting: Boolean = false,
    val isDebug: Boolean = false,
    val isFlat: Boolean = true,
    val portalCooldown: Int = 0,
    val seaLevel: Int = 63,
    /** Since 26.2. */
    val onlineMode: Boolean = false,
    val enforcesSecureChat: Boolean = false,
    /** Before 1.14 the difficulty is part of this packet (0 = peaceful). */
    val difficulty: Int = 0,
    /** Level keys the server has (1.16+); the joined dimension by default. */
    val worlds: List<String> = listOf(dimension.name),
) : ClientboundPacket {
    companion object Codec : PacketCodec<JoinGamePacket> {
        override fun encode(buffer: ByteBuf, packet: JoinGamePacket, version: ProtocolVersion) {
            when {
                version >= MINECRAFT_1_20_2 -> encodeModern(buffer, packet, version)
                version >= MINECRAFT_1_16 -> encodeRegistryEra(buffer, packet, version)
                else -> encodeLegacy(buffer, packet, version)
            }
        }

        private fun encodeLegacy(buffer: ByteBuf, packet: JoinGamePacket, version: ProtocolVersion) = with(buffer) {
            writeInt(packet.entityId)
            writeByte(packet.gameMode.id or if (packet.hardcore) 0x08 else 0)
            if (version >= MINECRAFT_1_9_1) writeInt(packet.dimension.legacyId) else writeByte(packet.dimension.legacyId)
            if (version >= MINECRAFT_1_15) writeLong(packet.hashedSeed)
            if (version < MINECRAFT_1_14) writeByte(packet.difficulty)
            writeByte(packet.maxPlayers.coerceAtMost(255))
            writeString(if (packet.isFlat) "flat" else "default", 16)
            if (version >= MINECRAFT_1_14) writeVarInt(packet.viewDistance)
            writeBoolean(packet.reducedDebugInfo)
            if (version >= MINECRAFT_1_15) writeBoolean(packet.enableRespawnScreen)
        }

        /** 1.16 - 1.20.1: the registry codec travels inside this packet. */
        private fun encodeRegistryEra(buffer: ByteBuf, packet: JoinGamePacket, version: ProtocolVersion) = with(buffer) {
            val dimension = packet.dimension
            writeInt(packet.entityId)
            if (version >= MINECRAFT_1_16_2) {
                writeBoolean(packet.hardcore)
                writeByte(packet.gameMode.id)
            } else {
                writeByte(packet.gameMode.id or if (packet.hardcore) 0x08 else 0)
            }
            writeByte(packet.previousGameMode?.id ?: -1)
            writeList(packet.worlds) { writeIdentifier(it) }
            writeCompound(requireNotNull(dimension.registryCodec) { "Registry codec required for $version" }, version)
            if (version >= MINECRAFT_1_16_2 && version < MINECRAFT_1_19) {
                writeCompound(requireNotNull(dimension.typeElement) { "Dimension type element required for $version" }, version)
            } else {
                writeIdentifier(dimension.type)
            }
            writeIdentifier(dimension.name)
            writeLong(packet.hashedSeed)
            if (version >= MINECRAFT_1_16_2) writeVarInt(packet.maxPlayers) else writeByte(packet.maxPlayers.coerceAtMost(255))
            writeVarInt(packet.viewDistance)
            if (version >= MINECRAFT_1_18) writeVarInt(packet.simulationDistance)
            writeBoolean(packet.reducedDebugInfo)
            writeBoolean(packet.enableRespawnScreen)
            writeBoolean(packet.isDebug)
            writeBoolean(packet.isFlat)
            if (version >= MINECRAFT_1_19) writeBoolean(false) // no last death location
            if (version >= MINECRAFT_1_20) writeVarInt(packet.portalCooldown)
        }

        /** 1.20.2+: registries moved to the configuration state. */
        private fun encodeModern(buffer: ByteBuf, packet: JoinGamePacket, version: ProtocolVersion) = with(buffer) {
            val dimension = packet.dimension
            writeInt(packet.entityId)
            writeBoolean(packet.hardcore)
            writeList(packet.worlds) { writeIdentifier(it) }
            writeVarInt(packet.maxPlayers)
            writeVarInt(packet.viewDistance)
            writeVarInt(packet.simulationDistance)
            writeBoolean(packet.reducedDebugInfo)
            writeBoolean(packet.enableRespawnScreen)
            writeBoolean(packet.doLimitedCrafting)
            if (version >= MINECRAFT_1_20_5) writeVarInt(dimension.typeId) else writeIdentifier(dimension.type)
            writeIdentifier(dimension.name)
            writeLong(packet.hashedSeed)
            if (version >= MINECRAFT_26_3) {
                // Varint game mode; the previous one is an optional varint: 0 = none, otherwise id + 1.
                writeVarInt(packet.gameMode.id)
                writeVarInt(packet.previousGameMode?.let { it.id + 1 } ?: 0)
            } else {
                writeByte(packet.gameMode.id)
                writeByte(packet.previousGameMode?.id ?: -1)
            }
            writeBoolean(packet.isDebug)
            writeBoolean(packet.isFlat)
            writeBoolean(false) // no last death location
            writeVarInt(packet.portalCooldown)
            if (version >= MINECRAFT_1_21_2) writeVarInt(packet.seaLevel)
            if (version >= MINECRAFT_26_2) writeBoolean(packet.onlineMode)
            if (version >= MINECRAFT_1_20_5) writeBoolean(packet.enforcesSecureChat)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): JoinGamePacket = when {
            version >= MINECRAFT_1_20_2 -> decodeModern(buffer, version)
            version >= MINECRAFT_1_16 -> decodeRegistryEra(buffer, version)
            else -> decodeLegacy(buffer, version)
        }

        private fun decodeLegacy(buffer: ByteBuf, version: ProtocolVersion): JoinGamePacket = with(buffer) {
            val entityId = readInt()
            val mode = readUnsignedByte().toInt()
            val legacyId = if (version >= MINECRAFT_1_9_1) readInt() else readByte().toInt()
            val seed = if (version >= MINECRAFT_1_15) readLong() else 0
            val difficulty = if (version < MINECRAFT_1_14) readUnsignedByte().toInt() else 0
            val maxPlayers = readUnsignedByte().toInt()
            val levelType = readString(16)
            val viewDistance = if (version >= MINECRAFT_1_14) readVarInt() else 0
            val reducedDebug = readBoolean()
            val respawnScreen = if (version >= MINECRAFT_1_15) readBoolean() else true
            JoinGamePacket(
                entityId = entityId, gameMode = gameMode(mode and 0x07), dimension = DimensionInfo(legacyId = legacyId),
                hardcore = mode and 0x08 != 0, hashedSeed = seed, maxPlayers = maxPlayers, viewDistance = viewDistance,
                reducedDebugInfo = reducedDebug, enableRespawnScreen = respawnScreen,
                isFlat = levelType.equals("flat", ignoreCase = true), difficulty = difficulty,
            )
        }

        private fun decodeRegistryEra(buffer: ByteBuf, version: ProtocolVersion): JoinGamePacket = with(buffer) {
            val entityId = readInt()
            val hardcore: Boolean
            val mode: Int
            if (version >= MINECRAFT_1_16_2) {
                hardcore = readBoolean()
                mode = readUnsignedByte().toInt()
            } else {
                val raw = readUnsignedByte().toInt()
                hardcore = raw and 0x08 != 0
                mode = raw and 0x07
            }
            val previous = readByte().toInt()
            val worlds = readList(64) { readIdentifier() }
            val codec = readCompound(version)
            var typeElement: CompoundBinaryTag? = null
            var type = worlds.first()
            if (version >= MINECRAFT_1_16_2 && version < MINECRAFT_1_19) typeElement = readCompound(version) else type = readIdentifier()
            val name = readIdentifier()
            val seed = readLong()
            val maxPlayers = if (version >= MINECRAFT_1_16_2) readVarInt() else readUnsignedByte().toInt()
            val viewDistance = readVarInt()
            val simulationDistance = if (version >= MINECRAFT_1_18) readVarInt() else viewDistance
            val reducedDebug = readBoolean()
            val respawnScreen = readBoolean()
            val debug = readBoolean()
            val flat = readBoolean()
            if (version >= MINECRAFT_1_19) readOptional { readIdentifier().also { readBlockPosition(version) } }
            val portalCooldown = if (version >= MINECRAFT_1_20) readVarInt() else 0
            JoinGamePacket(
                entityId = entityId, gameMode = gameMode(mode), hardcore = hardcore,
                previousGameMode = GameMode.byId(previous),
                dimension = DimensionInfo(type = type, name = name, registryCodec = codec, typeElement = typeElement), worlds = worlds,
                hashedSeed = seed, maxPlayers = maxPlayers, viewDistance = viewDistance,
                simulationDistance = simulationDistance, reducedDebugInfo = reducedDebug,
                enableRespawnScreen = respawnScreen, isDebug = debug, isFlat = flat, portalCooldown = portalCooldown,
            )
        }

        private fun decodeModern(buffer: ByteBuf, version: ProtocolVersion): JoinGamePacket = with(buffer) {
            val entityId = readInt()
            val hardcore = readBoolean()
            val worlds = readList(64) { readIdentifier() }
            val maxPlayers = readVarInt()
            val viewDistance = readVarInt()
            val simulationDistance = readVarInt()
            val reducedDebug = readBoolean()
            val respawnScreen = readBoolean()
            val limitedCrafting = readBoolean()
            var typeId = 0
            var type = "minecraft:overworld"
            if (version >= MINECRAFT_1_20_5) typeId = readVarInt() else type = readIdentifier()
            val name = readIdentifier()
            val seed = readLong()
            val mode = if (version >= MINECRAFT_26_3) readVarInt() else readUnsignedByte().toInt()
            val previous = if (version >= MINECRAFT_26_3) readVarInt() - 1 else readByte().toInt()
            val debug = readBoolean()
            val flat = readBoolean()
            readOptional { readIdentifier().also { readBlockPosition(version) } }
            val portalCooldown = readVarInt()
            val seaLevel = if (version >= MINECRAFT_1_21_2) readVarInt() else 63
            val onlineMode = version >= MINECRAFT_26_2 && readBoolean()
            val secureChat = version >= MINECRAFT_1_20_5 && readBoolean()
            JoinGamePacket(
                entityId = entityId, gameMode = gameMode(mode), hardcore = hardcore,
                previousGameMode = GameMode.byId(previous),
                dimension = DimensionInfo(type = type, name = name, typeId = typeId), worlds = worlds,
                hashedSeed = seed, maxPlayers = maxPlayers, viewDistance = viewDistance,
                simulationDistance = simulationDistance, reducedDebugInfo = reducedDebug,
                enableRespawnScreen = respawnScreen, doLimitedCrafting = limitedCrafting, isDebug = debug, isFlat = flat,
                portalCooldown = portalCooldown, seaLevel = seaLevel, onlineMode = onlineMode, enforcesSecureChat = secureChat,
            )
        }

        private fun gameMode(id: Int) = GameMode.byId(id) ?: GameMode.SURVIVAL
    }
}
