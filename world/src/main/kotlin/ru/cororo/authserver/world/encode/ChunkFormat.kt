package ru.cororo.authserver.world.encode

import ru.cororo.authserver.gamedata.VersionData
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.*

/** Every way the chunk packet layout changed between 1.8 and today, resolved for one version. */
internal class ChunkFormat(val version: ProtocolVersion, val data: VersionData) {
    enum class Era { V1_8, V1_9, V1_14, V1_18 }

    enum class Mask { USHORT, VAR_INT, BIT_SET, NONE }

    enum class Biomes { BYTES_256, INTS_256_IN_DATA, INTS_1024, VAR_INTS, IN_SECTIONS }

    enum class Heightmaps { NONE, NBT, ARRAY }

    val era: Era = when {
        version < MINECRAFT_1_9 -> Era.V1_8
        version < MINECRAFT_1_14 -> Era.V1_9
        version < MINECRAFT_1_18 -> Era.V1_14
        else -> Era.V1_18
    }

    val mask: Mask = when {
        version < MINECRAFT_1_9 -> Mask.USHORT
        version < MINECRAFT_1_17 -> Mask.VAR_INT
        version < MINECRAFT_1_18 -> Mask.BIT_SET
        else -> Mask.NONE
    }

    val biomes: Biomes = when {
        version < MINECRAFT_1_13 -> Biomes.BYTES_256
        version < MINECRAFT_1_15 -> Biomes.INTS_256_IN_DATA
        version < MINECRAFT_1_16_2 -> Biomes.INTS_1024
        version < MINECRAFT_1_18 -> Biomes.VAR_INTS
        else -> Biomes.IN_SECTIONS
    }

    val heightmaps: Heightmaps = when {
        version < MINECRAFT_1_14 -> Heightmaps.NONE
        version < MINECRAFT_1_21_5 -> Heightmaps.NBT
        else -> Heightmaps.ARRAY
    }

    /** Pre-1.16 packed arrays let values span two longs. */
    val spanning: Boolean = version < MINECRAFT_1_16

    /** `Full chunk` flag, removed in 1.17. */
    val fullChunkFlag: Boolean = version < MINECRAFT_1_17

    /** 1.16 - 1.16.1 only. */
    val ignoreOldLightFlag: Boolean = version == MINECRAFT_1_16 || version == MINECRAFT_1_16_1

    /** Light arrays inside the section data (before 1.14). */
    val lightInSections: Boolean = version < MINECRAFT_1_14

    /** Separate `Update Light` packet (1.14 - 1.17.1). */
    val lightPacket: Boolean = version >= MINECRAFT_1_14 && version < MINECRAFT_1_18

    /** Light data appended to the chunk packet (1.18+). */
    val lightInChunk: Boolean = version >= MINECRAFT_1_18

    /** `Trust edges` light flag: 1.16 - 1.19.4. */
    val trustEdges: Boolean = version >= MINECRAFT_1_16 && version < MINECRAFT_1_20

    val blockCount: Boolean = version >= MINECRAFT_1_14

    /** Sections also count fluid blocks since 26.1. */
    val fluidCount: Boolean = version >= MINECRAFT_26_1
    val blockEntities: Boolean = version >= MINECRAFT_1_9_4

    /** Paletted containers may encode a single value with zero bits (1.18+). */
    val singleValuePalette: Boolean = version >= MINECRAFT_1_18

    /** Packed data arrays carry their own length before 1.21.5. */
    val dataLengthPrefix: Boolean = version < MINECRAFT_1_21_5

    /** 1.9 - 1.12 write a zero palette length even for the direct palette; later versions omit it. */
    val directPaletteLength: Boolean = version < MINECRAFT_1_13

    val directBits: Int = if (version < MINECRAFT_1_13) 13 else BitPacking.bitsFor(data.blockStateCount)

    val minSection: Int = data.minY shr 4
    val sectionCount: Int = data.height shr 4
    val sections: IntRange = minSection until minSection + sectionCount

    /** Heightmap entries store `height - minY` in this many bits. */
    val heightmapBits: Int = BitPacking.bitsFor(data.height + 1)

    val plainsBiome: Int = data.plainsBiome

    companion object {
        const val MOTION_BLOCKING = 4
    }
}
