package ru.cororo.authserver.world.loader

import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.IntBinaryTag
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.world.World

/** Schematic-style files: a box of blocks placed relative to a [Placement]. */
internal object SchematicLoader {
    /**
     * Sponge schematic v2 (root `Schematic`: Palette, BlockData) or v3 (root `Schematic.Blocks`: Palette, Data).
     * Block data is a var-int stream of palette indices in `x + z * width + y * width * length` order.
     */
    fun loadSponge(root: CompoundBinaryTag, placement: Placement, world: World, statistics: LoadStatistics) {
        val schematic = if (root.get("Schematic") != null) root.getCompound("Schematic") else root
        val width = schematic.getShort("Width").toInt() and 0xFFFF
        val height = schematic.getShort("Height").toInt() and 0xFFFF
        val length = schematic.getShort("Length").toInt() and 0xFFFF
        val blocks = if (schematic.getInt("Version") >= 3) schematic.getCompound("Blocks") else schematic
        val paletteTag = blocks.getCompound("Palette")
        val palette = IntArray(paletteTag.size())
        for (key in paletteTag.keySet()) {
            val index = (paletteTag.get(key) as IntBinaryTag).value()
            palette[index] = statistics.resolve(key)
        }
        val data = blocks.getByteArray(if (schematic.getInt("Version") >= 3) "Data" else "BlockData")
        var offset = 0
        var index = 0
        while (offset < data.size && index < width * height * length) {
            var value = 0
            var shift = 0
            do {
                val byte = data[offset++].toInt()
                value = value or ((byte and 0x7F) shl shift)
                shift += 7
            } while (byte and 0x80 != 0)
            val x = index % width
            val z = (index / width) % length
            val y = index / (width * length)
            statistics.place(world, placement.x + x, placement.y + y, placement.z + z, palette[value])
            index++
        }
    }

    /** MCEdit / WorldEdit legacy schematic: numeric IDs in `(y * length + z) * width + x` order. */
    fun loadMcEdit(schematic: CompoundBinaryTag, placement: Placement, world: World, statistics: LoadStatistics) {
        val width = schematic.getShort("Width").toInt()
        val height = schematic.getShort("Height").toInt()
        val length = schematic.getShort("Length").toInt()
        val ids = schematic.getByteArray("Blocks")
        val metas = schematic.getByteArray("Data")
        val add = schematic.getByteArray("AddBlocks")
        for (index in ids.indices) {
            var id = ids[index].toInt() and 0xFF
            if (add.isNotEmpty()) id = id or ((add[index shr 1].toInt() shr (if (index and 1 == 0) 4 else 0) and 0xF) shl 8)
            if (id == 0) continue
            val x = index % width
            val z = (index / width) % length
            val y = index / (width * length)
            if (y >= height) break
            statistics.place(world, placement.x + x, placement.y + y, placement.z + z, GameData.blocks.legacy(id, metas[index].toInt()))
        }
    }

    /** Vanilla structure block file: a palette of `{Name, Properties}` and blocks with a `pos` and palette `state`. */
    fun loadStructure(structure: CompoundBinaryTag, placement: Placement, world: World, statistics: LoadStatistics) {
        val palette = structure.getList("palette").map { statistics.resolve(it.blockState()) }
        for (tag in structure.getList("blocks")) {
            val block = tag as CompoundBinaryTag
            val position = block.getList("pos")
            statistics.place(
                world,
                placement.x + position.getInt(0), placement.y + position.getInt(1), placement.z + position.getInt(2),
                palette[block.getInt("state")],
            )
        }
    }
}
