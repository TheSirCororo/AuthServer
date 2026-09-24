package ru.cororo.authserver.gamedata

import java.util.BitSet

/**
 * Block states of the newest supported release. Their network IDs are the server's canonical block IDs;
 * [VersionData.blockState] maps them to what a particular client understands.
 */
class BlockStates internal constructor(
    private val states: Array<String>,
    private val solid: BitSet,
    private val defaults: BitSet,
    private val legacy: IntArray,
    private val renamed: Map<String, Int>,
) {
    private val ids: Map<String, Int> = states.withIndex().associate { (id, state) -> state to id }
    private val byName: Map<String, IntArray> = states.indices.groupBy { nameOf(states[it]) }
        .mapValues { (_, ids) -> ids.toIntArray() }
    /** Old block name -> current name, learned from renamed states (e.g. `grass` -> `short_grass`). */
    private val renamedBlocks: Map<String, String> = renamed.entries.associate { (old, id) -> nameOf(old) to nameOf(states[id]) }

    val size: Int get() = states.size

    fun state(id: Int): String = states[id]

    fun isSolid(id: Int): Boolean = solid[id]

    /** Whether the state contains a fluid: water, lava or a waterlogged block. */
    fun isFluid(id: Int): Boolean = fluids[id]

    private val fluids: BitSet by lazy {
        BitSet(states.size).apply {
            states.forEachIndexed { id, state ->
                if (nameOf(state) in FLUID_BLOCKS || "waterlogged=true" in state) set(id)
            }
        }
    }

    fun isAir(id: Int): Boolean = nameOf(states[id]).let { it == "minecraft:air" || it == "minecraft:cave_air" || it == "minecraft:void_air" }

    /** Canonical ID of a pre-1.13 block, or [AIR] when the combination never existed. */
    fun legacy(block: Int, meta: Int): Int = legacy.getOrElse((block shl 4) or (meta and 15)) { AIR }.takeIf { it >= 0 } ?: AIR

    /**
     * Resolves a block state string leniently: namespaces and unspecified properties default like in commands,
     * names from older releases are upgraded. Returns `null` for unknown blocks.
     */
    fun parse(state: String): Int? {
        val normalised = normalise(state)
        ids[normalised]?.let { return it }
        renamed[normalised]?.let { return it }
        val name = nameOf(normalised)
        val properties = propertiesOf(normalised)
        val candidates = byName[name] ?: byName[renamedBlocks[name]] ?: return null
        val default = candidates.firstOrNull(defaults::get) ?: candidates.first()
        val defaultProperties = propertiesOf(states[default])
        return candidates.maxBy { candidate ->
            val candidateProperties = propertiesOf(states[candidate])
            val requested = properties.count { (key, value) -> candidateProperties[key] == value }
            val kept = defaultProperties.count { (key, value) -> key !in properties && candidateProperties[key] == value }
            requested * 1000 + kept
        }
    }

    companion object {
        const val AIR = 0

        private val FLUID_BLOCKS = setOf(
            "minecraft:water", "minecraft:lava", "minecraft:bubble_column", "minecraft:kelp", "minecraft:kelp_plant",
            "minecraft:seagrass", "minecraft:tall_seagrass",
        )

        internal fun load(): BlockStates {
            val lines = Resources.gzipLines("blocks.txt.gz")
            val states = Array(lines.size) { "" }
            val solid = BitSet(lines.size)
            val defaults = BitSet(lines.size)
            lines.forEachIndexed { id, line ->
                val (state, isSolid, isDefault) = line.split('\t')
                states[id] = state
                solid[id] = isSolid == "1"
                defaults[id] = isDefault == "1"
            }
            val renamed = Resources.gzipLines("renames.txt.gz").associate { line ->
                val (old, id) = line.split('\t')
                old to id.toInt()
            }
            return BlockStates(states, solid, defaults, Resources.gzipInts("legacy-blocks.bin.gz"), renamed)
        }

        fun nameOf(state: String): String = state.substringBefore('[')

        fun propertiesOf(state: String): Map<String, String> {
            if ('[' !in state) return emptyMap()
            return state.substringAfter('[').removeSuffix("]").split(',').filter(String::isNotBlank).associate { pair ->
                pair.substringBefore('=').trim() to pair.substringAfter('=').trim()
            }
        }

        /** Adds the namespace and sorts properties, matching the generated resource format. */
        fun normalise(state: String): String {
            val trimmed = state.trim()
            val name = nameOf(trimmed).lowercase().let { if (':' in it) it else "minecraft:$it" }
            val properties = propertiesOf(trimmed).toSortedMap()
            return if (properties.isEmpty()) name else properties.entries.joinToString(",", "$name[", "]") { "${it.key}=${it.value}" }
        }
    }
}
