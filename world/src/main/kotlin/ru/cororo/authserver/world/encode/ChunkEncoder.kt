package ru.cororo.authserver.world.encode

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.LongArrayBinaryTag
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.gamedata.VersionData
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.buffer.writeBitSet
import ru.cororo.authserver.protocol.buffer.writeNbt
import ru.cororo.authserver.protocol.buffer.writeVarInt
import ru.cororo.authserver.protocol.packet.play.ChunkDataPacket
import ru.cororo.authserver.protocol.packet.play.LightUpdatePacket
import ru.cororo.authserver.world.Chunk
import ru.cororo.authserver.world.ChunkSection
import ru.cororo.authserver.world.encode.ChunkFormat.Biomes
import ru.cororo.authserver.world.encode.ChunkFormat.Era
import ru.cororo.authserver.world.encode.ChunkFormat.Heightmaps
import ru.cororo.authserver.world.encode.ChunkFormat.Mask
import java.util.BitSet

private const val LIGHT_BYTES = 2048
private const val FULL_LIGHT: Byte = -1 // two nibbles of 15

/**
 * Encodes chunks for one client version: canonical block states are mapped to the client's IDs, the world is
 * fully sky-lit and every column uses the plains biome. Blocks outside the client's world height are dropped.
 */
class ChunkEncoder(version: ProtocolVersion, data: VersionData = GameData.version(version)) {
    private val format = ChunkFormat(version, data)
    private val version = version

    fun encode(chunk: Chunk): List<ClientboundPacket> {
        val body = Unpooled.buffer()
        try {
            when (format.era) {
                Era.V1_8 -> writeLegacy18(body, chunk)
                Era.V1_9 -> writePaletted(body, chunk)
                Era.V1_14, Era.V1_18 -> writeModern(body, chunk)
            }
            val packets = mutableListOf<ClientboundPacket>(ChunkDataPacket(chunk.x, chunk.z, body.toByteArray()))
            if (format.lightPacket) packets.add(0, LightUpdatePacket(chunk.x, chunk.z, lightBody()))
            return packets
        } finally {
            body.release()
        }
    }

    /** Client block state IDs of a section in YZX order. */
    private fun clientStates(section: ChunkSection?): IntArray {
        if (section == null) return IntArray(ChunkSection.BLOCKS) { format.data.blockState(0) }
        val states = section.states()
        for (index in states.indices) states[index] = format.data.blockState(states[index])
        return states
    }

    private fun fluidBlocks(section: ChunkSection): Int = section.states().count(GameData.blocks::isFluid)

    private fun presentSections(chunk: Chunk): List<Int> =
        format.sections.filter { chunk.section(it)?.isEmpty == false }

    // ------------------------------------------------------------------------------------------------ 1.8

    private fun writeLegacy18(body: ByteBuf, chunk: Chunk) {
        val sections = presentSections(chunk)
        val data = Unpooled.buffer()
        try {
            for (sectionY in sections) clientStates(chunk.section(sectionY)).forEach { data.writeShortLE(it) }
            repeat(sections.size) { data.writeZero(LIGHT_BYTES) } // block light
            repeat(sections.size) { data.writeLight() } // sky light
            repeat(256) { data.writeByte(format.plainsBiome) }
            body.writeBoolean(true)
            body.writeShort(sectionMask(sections))
            body.writeVarInt(data.readableBytes())
            body.writeBytes(data)
        } finally {
            data.release()
        }
    }

    // ------------------------------------------------------------------------------------------------ 1.9 - 1.13

    private fun writePaletted(body: ByteBuf, chunk: Chunk) {
        val sections = presentSections(chunk)
        val data = Unpooled.buffer()
        try {
            for (sectionY in sections) {
                writeBlockContainer(data, clientStates(chunk.section(sectionY)))
                data.writeZero(LIGHT_BYTES)
                data.writeLight()
            }
            writeColumnBiomes(data)
            body.writeBoolean(true)
            body.writeVarInt(sectionMask(sections))
            body.writeVarInt(data.readableBytes())
            body.writeBytes(data)
            if (format.blockEntities) body.writeVarInt(0)
        } finally {
            data.release()
        }
    }

    // ------------------------------------------------------------------------------------------------ 1.14+

    private fun writeModern(body: ByteBuf, chunk: Chunk) {
        val sections = if (format.era == Era.V1_18) format.sections.toList() else presentSections(chunk)
        if (format.fullChunkFlag) body.writeBoolean(true)
        if (format.ignoreOldLightFlag) body.writeBoolean(true)
        when (format.mask) {
            Mask.VAR_INT -> body.writeVarInt(sectionMask(sections))
            Mask.BIT_SET -> body.writeBitSet(BitSet().apply { sections.forEach { set(it - format.minSection) } }, version)
            else -> Unit
        }
        writeHeightmaps(body, chunk)
        when (format.biomes) {
            Biomes.INTS_1024 -> repeat(1024) { body.writeInt(format.plainsBiome) }
            Biomes.VAR_INTS -> {
                val count = format.sectionCount * 64
                body.writeVarInt(count)
                repeat(count) { body.writeVarInt(format.plainsBiome) }
            }
            else -> Unit
        }
        val data = Unpooled.buffer()
        try {
            for (sectionY in sections) {
                val section = chunk.section(sectionY)
                data.writeShort(section?.nonAirBlocks ?: 0)
                if (format.fluidCount) data.writeShort(section?.let(::fluidBlocks) ?: 0)
                writeBlockContainer(data, clientStates(section))
                if (format.biomes == Biomes.IN_SECTIONS) writeSingleValue(data, format.plainsBiome)
            }
            if (format.biomes == Biomes.INTS_256_IN_DATA) repeat(256) { data.writeInt(format.plainsBiome) }
            body.writeVarInt(data.readableBytes())
            body.writeBytes(data)
        } finally {
            data.release()
        }
        body.writeVarInt(0) // block entities
        if (format.lightInChunk) writeLightData(body, sectionCountBitSet = true)
    }

    private fun writeHeightmaps(body: ByteBuf, chunk: Chunk) {
        val heights = chunk.heights().map { height ->
            if (height == Int.MIN_VALUE) 0 else (height - format.data.minY).coerceIn(0, format.data.height)
        }.toIntArray()
        val packed = BitPacking.pack(heights, format.heightmapBits, format.spanning)
        when (format.heightmaps) {
            Heightmaps.NBT -> body.writeNbt(
                CompoundBinaryTag.builder().put("MOTION_BLOCKING", LongArrayBinaryTag.longArrayBinaryTag(*packed)).build(),
                version,
            )
            Heightmaps.ARRAY -> {
                body.writeVarInt(1)
                body.writeVarInt(ChunkFormat.MOTION_BLOCKING)
                body.writeVarInt(packed.size)
                packed.forEach(body::writeLong)
            }
            Heightmaps.NONE -> Unit
        }
    }

    private fun writeColumnBiomes(data: ByteBuf) = when (format.biomes) {
        Biomes.BYTES_256 -> repeat(256) { data.writeByte(format.plainsBiome) }
        Biomes.INTS_256_IN_DATA -> repeat(256) { data.writeInt(format.plainsBiome) }
        else -> Unit
    }

    // ------------------------------------------------------------------------------------------------ palettes

    private fun writeBlockContainer(buffer: ByteBuf, states: IntArray) {
        val palette = LinkedHashMap<Int, Int>()
        val indices = IntArray(states.size) { palette.getOrPut(states[it]) { palette.size } }
        when {
            palette.size == 1 && format.singleValuePalette -> writeSingleValue(buffer, states[0])
            palette.size <= 256 -> {
                val bits = maxOf(4, BitPacking.bitsFor(palette.size))
                buffer.writeByte(bits)
                buffer.writeVarInt(palette.size)
                palette.keys.forEach(buffer::writeVarInt)
                writeData(buffer, BitPacking.pack(indices, bits, format.spanning))
            }
            else -> {
                buffer.writeByte(format.directBits)
                if (format.directPaletteLength) buffer.writeVarInt(0)
                writeData(buffer, BitPacking.pack(states, format.directBits, format.spanning))
            }
        }
    }

    private fun writeSingleValue(buffer: ByteBuf, value: Int) {
        buffer.writeByte(0)
        buffer.writeVarInt(value)
        if (format.dataLengthPrefix) buffer.writeVarInt(0)
    }

    private fun writeData(buffer: ByteBuf, data: LongArray) {
        if (format.dataLengthPrefix) buffer.writeVarInt(data.size)
        data.forEach(buffer::writeLong)
    }

    // ------------------------------------------------------------------------------------------------ light

    /** 1.14 - 1.17.1 `Update Light` body after the chunk coordinates. */
    private fun lightBody(): ByteArray {
        val body = Unpooled.buffer()
        try {
            if (format.trustEdges) body.writeBoolean(true)
            writeLightData(body, sectionCountBitSet = format.mask == Mask.BIT_SET)
            return body.toByteArray()
        } finally {
            body.release()
        }
    }

    /**
     * Sky light is full everywhere, block light is zero. Masks cover the world's sections plus one below and above.
     * Before 1.17 masks are var ints and arrays follow without a count.
     */
    private fun writeLightData(body: ByteBuf, sectionCountBitSet: Boolean) {
        if (format.lightInChunk && format.trustEdges) body.writeBoolean(true)
        val sections = format.sectionCount + 2
        val all = BitSet().apply { set(0, sections) }
        if (sectionCountBitSet) {
            body.writeBitSet(all, version)
            body.writeBitSet(BitSet(), version)
            body.writeBitSet(BitSet(), version)
            body.writeBitSet(all, version)
            body.writeVarInt(sections)
        } else {
            val mask = (1 shl sections) - 1
            body.writeVarInt(mask)
            body.writeVarInt(0)
            body.writeVarInt(0)
            body.writeVarInt(mask)
        }
        repeat(sections) {
            body.writeVarInt(LIGHT_BYTES)
            body.writeLight()
        }
        if (sectionCountBitSet) body.writeVarInt(0) // no block light arrays
    }

    private fun sectionMask(sections: List<Int>): Int = sections.fold(0) { mask, y -> mask or (1 shl (y - format.minSection)) }
}

private fun ByteBuf.writeLight() {
    repeat(LIGHT_BYTES) { writeByte(FULL_LIGHT.toInt()) }
}

internal fun ByteBuf.toByteArray(): ByteArray = ByteArray(readableBytes()).also { getBytes(readerIndex(), it) }
