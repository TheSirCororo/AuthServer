package ru.cororo.authserver.world.loader

import net.kyori.adventure.nbt.BinaryTag
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import org.slf4j.LoggerFactory
import ru.cororo.authserver.gamedata.BlockStates
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.world.World
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Where a loaded map is placed: its minimum corner lands on [x], [y], [z]. */
data class Placement(val x: Int = 0, val y: Int = 0, val z: Int = 0)

/**
 * Loads maps into a [World] of canonical block states. Supported formats: Sponge schematics (.schem, v2 and v3),
 * MCEdit schematics (.schematic), vanilla structures (.nbt) and Anvil worlds (a world or `region` directory).
 */
object MapLoader {
    private val logger = LoggerFactory.getLogger(MapLoader::class.java)

    fun load(path: Path, placement: Placement = Placement(), world: World = World(path.name)): World {
        require(Files.exists(path)) { "Map $path does not exist" }
        val statistics = LoadStatistics()
        when {
            path.isDirectory() -> AnvilLoader.load(path, world, statistics)
            path.extension.equals("schem", ignoreCase = true) -> SchematicLoader.loadSponge(read(path), placement, world, statistics)
            path.extension.equals("schematic", ignoreCase = true) -> SchematicLoader.loadMcEdit(read(path), placement, world, statistics)
            path.extension.equals("nbt", ignoreCase = true) -> SchematicLoader.loadStructure(read(path), placement, world, statistics)
            else -> throw IllegalArgumentException("Unsupported map format: $path")
        }
        logger.info("Loaded map {}: {} blocks in {} chunks", path.name, statistics.blocks, world.chunks().size)
        if (statistics.unknown.isNotEmpty()) {
            logger.warn("Unknown block states were replaced by air: {}", statistics.unknown.entries
                .sortedByDescending { it.value }.take(10).joinToString { "${it.key} x${it.value}" })
        }
        return world
    }

    /** Schematic and structure files are gzip-compressed NBT; plain NBT is accepted too. */
    internal fun read(path: Path): CompoundBinaryTag = runCatching {
        BinaryTagIO.unlimitedReader().read(path, BinaryTagIO.Compression.GZIP)
    }.getOrElse { BinaryTagIO.unlimitedReader().read(path, BinaryTagIO.Compression.NONE) }
}

internal class LoadStatistics {
    var blocks = 0
    val unknown = HashMap<String, Int>()
    private val cache = HashMap<String, Int>()

    /** Canonical state for a block state string, counting unknown ones. */
    fun resolve(state: String): Int = cache.getOrPut(state) {
        GameData.blocks.parse(state) ?: run {
            unknown.merge(BlockStates.nameOf(state), 1, Int::plus)
            BlockStates.AIR
        }
    }

    fun place(world: World, x: Int, y: Int, z: Int, state: Int) {
        if (state == BlockStates.AIR) return
        world.setBlock(x, y, z, state)
        blocks++
    }
}

/**
 * A palette entry as a block state string. Entries are `{Name, Properties}` compounds, `{id, properties}` since 26.x
 * and plain state strings in 26.3 chunk palettes.
 */
internal fun BinaryTag.blockState(): String {
    if (this is StringBinaryTag) return value()
    val compound = this as CompoundBinaryTag
    val name = compound.getString("Name").ifEmpty { compound.getString("id") }
    val properties = if (compound.get("Properties") != null) compound.getCompound("Properties") else compound.getCompound("properties")
    if (properties.size() == 0) return name
    return properties.keySet().sorted().joinToString(",", "$name[", "]") { "$it=${properties.getString(it)}" }
}
