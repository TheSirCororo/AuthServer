package ru.cororo.authserver.world

import ru.cororo.authserver.gamedata.BlockStates

/** 16x16x16 blocks of canonical block state IDs (see [BlockStates]). */
class ChunkSection {
    private val states = IntArray(BLOCKS)

    /** Blocks that are not air; sections with none are omitted or sent as empty. */
    var nonAirBlocks: Int = 0
        private set

    operator fun get(x: Int, y: Int, z: Int): Int = states[index(x, y, z)]

    operator fun set(x: Int, y: Int, z: Int, state: Int) {
        val index = index(x, y, z)
        val previous = states[index]
        if (previous == BlockStates.AIR && state != BlockStates.AIR) nonAirBlocks++
        if (previous != BlockStates.AIR && state == BlockStates.AIR) nonAirBlocks--
        states[index] = state
    }

    /** States in YZX order, the layout every network format uses. */
    fun states(): IntArray = states.copyOf()

    val isEmpty: Boolean get() = nonAirBlocks == 0

    companion object {
        const val BLOCKS = 4096

        fun index(x: Int, y: Int, z: Int) = (y shl 8) or (z shl 4) or x
    }
}

/** A chunk column; sections are keyed by absolute section Y (`blockY shr 4`). */
class Chunk(val x: Int, val z: Int) {
    private val sections = sortedMapOf<Int, ChunkSection>()

    fun section(sectionY: Int): ChunkSection? = sections[sectionY]

    operator fun get(x: Int, y: Int, z: Int): Int = sections[y shr 4]?.get(x, y and 15, z) ?: BlockStates.AIR

    operator fun set(x: Int, y: Int, z: Int, state: Int) {
        if (state == BlockStates.AIR && y shr 4 !in sections) return
        sections.getOrPut(y shr 4) { ChunkSection() }[x, y and 15, z] = state
    }

    /** Highest non-air block Y + 1 per column (index `z * 16 + x`), or [Int.MIN_VALUE] for empty columns. */
    fun heights(): IntArray {
        val heights = IntArray(256) { Int.MIN_VALUE }
        for ((sectionY, section) in sections.entries.reversed()) {
            if (section.isEmpty) continue
            for (column in 0 until 256) {
                if (heights[column] != Int.MIN_VALUE) continue
                for (y in 15 downTo 0) {
                    if (section[column and 15, y, column shr 4] != BlockStates.AIR) {
                        heights[column] = (sectionY shl 4) + y + 1
                        break
                    }
                }
            }
        }
        return heights
    }

    val isEmpty: Boolean get() = sections.values.all(ChunkSection::isEmpty)
}

/**
 * A static world made of canonical block states. It is built once when a map loads and then only read,
 * so it is safe to share between connections.
 */
class World(val name: String = "limbo") {
    private val chunks = HashMap<Long, Chunk>()

    fun chunk(chunkX: Int, chunkZ: Int): Chunk? = chunks[key(chunkX, chunkZ)]

    fun chunks(): Collection<Chunk> = chunks.values

    fun getBlock(x: Int, y: Int, z: Int): Int = chunks[key(x shr 4, z shr 4)]?.get(x and 15, y, z and 15) ?: BlockStates.AIR

    fun setBlock(x: Int, y: Int, z: Int, state: Int) {
        if (state == BlockStates.AIR && key(x shr 4, z shr 4) !in chunks) return
        chunks.getOrPut(key(x shr 4, z shr 4)) { Chunk(x shr 4, z shr 4) }[x and 15, y, z and 15] = state
    }

    /** Lowest and highest block Y that contain blocks, or `null` for an empty world. */
    fun heightRange(): IntRange? {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (chunk in chunks.values) {
            for (sectionY in -64..63) {
                val section = chunk.section(sectionY) ?: continue
                if (section.isEmpty) continue
                min = minOf(min, sectionY shl 4)
                max = maxOf(max, (sectionY shl 4) + 15)
            }
        }
        return if (min == Int.MAX_VALUE) null else min..max
    }

    private fun key(chunkX: Int, chunkZ: Int): Long = (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)
}
