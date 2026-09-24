package ru.cororo.authserver.server.world

import ru.cororo.authserver.api.player.Position
import ru.cororo.authserver.api.world.LimboWorld
import ru.cororo.authserver.gamedata.BlockStates
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.server.config.WorldConfig
import ru.cororo.authserver.world.Chunk
import ru.cororo.authserver.world.World
import ru.cororo.authserver.world.encode.ChunkEncoder
import ru.cororo.authserver.world.loader.MapLoader
import ru.cororo.authserver.world.loader.Placement
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * The limbo world and its per-version chunk packets. Packets are encoded once per client version on first use
 * and shared by every player of that version.
 */
class LimboWorldService(private val config: WorldConfig, baseDirectory: Path) : LimboWorld {
    private val world: World = if (config.map.isBlank()) platform() else MapLoader.load(
        baseDirectory.resolve(config.map), Placement(config.placement.x, config.placement.y, config.placement.z),
    )
    private val packets = ConcurrentHashMap<ProtocolVersion, List<ClientboundPacket>>()

    override val spawn = Position(config.spawn.x, config.spawn.y, config.spawn.z, config.spawn.yaw, config.spawn.pitch)

    override fun blockAt(x: Int, y: Int, z: Int): String = GameData.blocks.state(world.getBlock(x, y, z))

    override fun isSolid(x: Int, y: Int, z: Int): Boolean = GameData.blocks.isSolid(world.getBlock(x, y, z))

    /** Chunk packets around spawn for [version], in the order they should be sent (nearest first). */
    fun chunkPackets(version: ProtocolVersion): List<ClientboundPacket> = packets.computeIfAbsent(version) {
        val encoder = ChunkEncoder(version)
        val centerX = floor(spawn.x).toInt() shr 4
        val centerZ = floor(spawn.z).toInt() shr 4
        val radius = config.viewDistance
        val positions = (-radius..radius).flatMap { dx -> (-radius..radius).map { dz -> centerX + dx to centerZ + dz } }
            .sortedBy { (x, z) -> (x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ) }
        positions.flatMap { (x, z) ->
            val chunk = world.chunk(x, z)
            when {
                chunk != null -> encoder.encode(chunk)
                // Newer clients wait for the chunk they stand in; 1.8 treats an empty chunk as an unload.
                version > ProtocolVersion.MINECRAFT_1_8 -> encoder.encode(Chunk(x, z))
                else -> emptyList()
            }
        }
    }

    val chunkCenter: Pair<Int, Int> get() = (floor(spawn.x).toInt() shr 4) to (floor(spawn.z).toInt() shr 4)

    /** Without a map, players stand on a single barrier block in the void. */
    private fun platform(): World = World("void").apply {
        val barrier = GameData.blocks.parse("minecraft:barrier") ?: BlockStates.AIR
        setBlock(floor(config.spawn.x).toInt(), floor(config.spawn.y).toInt() - 1, floor(config.spawn.z).toInt(), barrier)
    }
}
