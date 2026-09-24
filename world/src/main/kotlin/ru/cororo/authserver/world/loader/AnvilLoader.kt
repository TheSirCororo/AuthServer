package ru.cororo.authserver.world.loader

import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.world.World
import ru.cororo.authserver.world.encode.BitPacking
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Anvil worlds (the overworld of a world directory, or a `region` directory): every chunk of every region file is
 * loaded, so the world should contain only the lobby.
 * Chunk formats of all eras are understood: pre-1.13 numeric sections, 1.13 - 1.17 `Level.Sections` and
 * 1.18+ `sections[].block_states`.
 */
internal object AnvilLoader {
    private const val SECTOR = 4096

    /** Data version of 20w17a, after which packed block states stopped spanning longs. */
    private const val NON_SPANNING_SINCE = 2527

    fun load(directory: Path, world: World, statistics: LoadStatistics) {
        // 26.1 moved the overworld into dimensions/minecraft/overworld.
        val regions = listOf(directory, directory.resolve("region"), directory.resolve("dimensions/minecraft/overworld/region"))
            .firstOrNull { it.name == "region" && it.isDirectory() }
            ?: throw IllegalArgumentException("$directory is neither a world nor a region directory")
        Files.list(regions).use { files ->
            files.filter { it.name.endsWith(".mca") }.sorted().forEach { loadRegion(it, world, statistics) }
        }
    }

    private fun loadRegion(file: Path, world: World, statistics: LoadStatistics) {
        RandomAccessFile(file.toFile(), "r").use { region ->
            if (region.length() < 2 * SECTOR) return
            val locations = IntArray(1024) { region.readInt() }
            for (location in locations) {
                if (location == 0) continue
                region.seek((location ushr 8).toLong() * SECTOR)
                val length = region.readInt()
                if (length <= 1) continue
                val compression = region.readByte().toInt()
                val data = ByteArray(length - 1).also(region::readFully)
                val stream = when (compression) {
                    1 -> GZIPInputStream(ByteArrayInputStream(data))
                    2 -> InflaterInputStream(ByteArrayInputStream(data))
                    3 -> ByteArrayInputStream(data)
                    else -> throw IllegalArgumentException("Unsupported chunk compression $compression in ${file.name}; " +
                        "re-save the world with zlib compression")
                }
                loadChunk(stream.use { BinaryTagIO.unlimitedReader().read(it) }, world, statistics)
            }
        }
    }

    private fun loadChunk(root: CompoundBinaryTag, world: World, statistics: LoadStatistics) {
        val dataVersion = root.getInt("DataVersion", 0)
        val level = if (root.get("Level") != null) root.getCompound("Level") else root
        val chunkX = level.getInt("xPos")
        val chunkZ = level.getInt("zPos")
        val sections = if (level.get("sections") != null) level.getList("sections") else level.getList("Sections")
        for (sectionTag in sections) {
            val section = sectionTag as CompoundBinaryTag
            val baseY = section.getByte("Y") * 16
            val states = when {
                section.get("block_states") != null -> palettedStates(
                    section.getCompound("block_states").getList("palette"),
                    section.getCompound("block_states").getLongArray("data"), spanning = false, statistics,
                )
                section.get("Palette") != null -> palettedStates(
                    section.getList("Palette"), section.getLongArray("BlockStates"),
                    spanning = dataVersion < NON_SPANNING_SINCE, statistics,
                )
                section.get("Blocks") != null -> legacyStates(section)
                else -> continue
            }
            for (index in states.indices) {
                statistics.place(world, chunkX * 16 + (index and 15), baseY + (index shr 8), chunkZ * 16 + ((index shr 4) and 15), states[index])
            }
        }
    }

    private fun palettedStates(
        paletteTag: net.kyori.adventure.nbt.ListBinaryTag, data: LongArray, spanning: Boolean, statistics: LoadStatistics,
    ): IntArray {
        val palette = paletteTag.map { statistics.resolve(it.blockState()) }
        if (palette.size <= 1 || data.isEmpty()) return IntArray(4096) { palette.firstOrNull() ?: 0 }
        val bits = maxOf(4, BitPacking.bitsFor(palette.size))
        return BitPacking.unpack(data, bits, 4096, spanning).map { palette.getOrElse(it) { 0 } }.toIntArray()
    }

    private fun legacyStates(section: CompoundBinaryTag): IntArray {
        val blocks = section.getByteArray("Blocks")
        val metas = section.getByteArray("Data")
        val add = section.getByteArray("Add")
        return IntArray(4096) { index ->
            var id = blocks[index].toInt() and 0xFF
            if (add.isNotEmpty()) id = id or (nibble(add, index) shl 8)
            GameData.blocks.legacy(id, nibble(metas, index))
        }
    }

    private fun nibble(array: ByteArray, index: Int): Int = (array[index shr 1].toInt() shr ((index and 1) * 4)) and 0xF
}
