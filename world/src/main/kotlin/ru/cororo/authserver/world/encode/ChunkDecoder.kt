package ru.cororo.authserver.world.encode

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.gamedata.VersionData
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.buffer.readBitSet
import ru.cororo.authserver.protocol.buffer.readNbt
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.world.ChunkSection
import ru.cororo.authserver.world.encode.ChunkFormat.Biomes
import ru.cororo.authserver.world.encode.ChunkFormat.Era
import ru.cororo.authserver.world.encode.ChunkFormat.Heightmaps
import ru.cororo.authserver.world.encode.ChunkFormat.Mask

/**
 * Reads chunk packet bodies back into client block state IDs. It exists to verify [ChunkEncoder] against real
 * vanilla packets, so it only understands full chunks and ignores block entities.
 */
internal class ChunkDecoder(version: ProtocolVersion, data: VersionData = GameData.version(version)) {
    private val format = ChunkFormat(version, data)
    private val version = version

    /** Section Y -> client block states (YZX order) of the Chunk Data body after its coordinates. */
    fun decode(body: ByteArray): Map<Int, IntArray> {
        val buffer = Unpooled.wrappedBuffer(body)
        val sections = when (format.era) {
            Era.V1_8 -> decodeLegacy18(buffer)
            Era.V1_9 -> decodePaletted(buffer)
            Era.V1_14, Era.V1_18 -> decodeModern(buffer)
        }
        return sections
    }

    private fun decodeLegacy18(buffer: ByteBuf): Map<Int, IntArray> {
        check(buffer.readBoolean()) { "Only full chunks are supported" }
        val sections = maskSections(buffer.readUnsignedShort())
        buffer.readVarInt()
        val result = sections.associateWith { IntArray(ChunkSection.BLOCKS) { buffer.readShortLE().toInt() and 0xFFFF } }
        buffer.skipBytes(sections.size * 2048 * 2 + 256)
        return result
    }

    private fun decodePaletted(buffer: ByteBuf): Map<Int, IntArray> {
        check(buffer.readBoolean()) { "Only full chunks are supported" }
        val sections = maskSections(buffer.readVarInt())
        val size = buffer.readVarInt()
        val start = buffer.readerIndex()
        val result = sections.associateWith {
            readContainer(buffer).also { buffer.skipBytes(2048 * 2) }
        }
        buffer.skipBytes(if (format.biomes == Biomes.BYTES_256) 256 else 1024)
        check(buffer.readerIndex() - start <= size) { "Section data overrun" }
        buffer.readerIndex(start + size) // 1.9 pads the buffer like later versions do

        if (format.blockEntities) skipBlockEntities(buffer)
        return result
    }

    private fun decodeModern(buffer: ByteBuf): Map<Int, IntArray> {
        if (format.fullChunkFlag) check(buffer.readBoolean()) { "Only full chunks are supported" }
        if (format.ignoreOldLightFlag) buffer.readBoolean()
        val sections = when (format.mask) {
            Mask.VAR_INT -> maskSections(buffer.readVarInt())
            Mask.BIT_SET -> buffer.readBitSet(version).stream().map { it + format.minSection }.toArray().toList()
            else -> format.sections.toList()
        }
        when (format.heightmaps) {
            Heightmaps.NBT -> buffer.readNbt(version)
            Heightmaps.ARRAY -> repeat(buffer.readVarInt()) {
                buffer.readVarInt()
                buffer.skipBytes(buffer.readVarInt() * 8)
            }
            Heightmaps.NONE -> Unit
        }
        when (format.biomes) {
            Biomes.INTS_1024 -> buffer.skipBytes(1024 * 4)
            Biomes.VAR_INTS -> repeat(buffer.readVarInt()) { buffer.readVarInt() }
            else -> Unit
        }
        val size = buffer.readVarInt()
        val start = buffer.readerIndex()
        val result = sections.associateWith {
            buffer.readShort()
            if (format.fluidCount) buffer.readShort()
            readContainer(buffer).also { if (format.biomes == Biomes.IN_SECTIONS) readContainer(buffer, biomes = true) }
        }
        if (format.biomes == Biomes.INTS_256_IN_DATA) buffer.skipBytes(256 * 4)
        // Vanilla over-estimates the section buffer size and pads it with zeros; the client skips the rest.
        check(buffer.readerIndex() - start <= size) { "Section data overrun: read ${buffer.readerIndex() - start} of $size" }
        buffer.readerIndex(start + size)
        skipBlockEntities(buffer)
        if (format.lightInChunk) skipLight(buffer)
        if (buffer.isReadable) throw DecoderException("${buffer.readableBytes()} trailing bytes")
        return result
    }

    /**
     * Parses an `Update Light` body (1.14 - 1.17.1, after the coordinates) and returns the number of sky light arrays.
     */
    fun decodeLightPacket(body: ByteArray): Int {
        val buffer = Unpooled.wrappedBuffer(body)
        if (format.trustEdges) buffer.readBoolean()
        val skyArrays: Int
        if (format.mask == Mask.BIT_SET) {
            val masks = List(4) { buffer.readBitSet(version) }
            skyArrays = buffer.readVarInt()
            check(skyArrays == masks[0].cardinality()) { "Sky light array count does not match its mask" }
            repeat(skyArrays) { buffer.skipBytes(buffer.readVarInt()) }
            repeat(buffer.readVarInt()) { buffer.skipBytes(buffer.readVarInt()) }
        } else {
            val skyMask = buffer.readVarInt()
            val blockMask = buffer.readVarInt()
            buffer.readVarInt()
            buffer.readVarInt()
            skyArrays = Integer.bitCount(skyMask)
            repeat(skyArrays + Integer.bitCount(blockMask)) { check(buffer.readVarInt() == 2048); buffer.skipBytes(2048) }
        }
        if (buffer.isReadable) throw DecoderException("${buffer.readableBytes()} trailing bytes in light data")
        return skyArrays
    }

    private fun readContainer(buffer: ByteBuf, biomes: Boolean = false): IntArray {
        val entries = if (biomes) 64 else ChunkSection.BLOCKS
        val bits = buffer.readUnsignedByte().toInt()
        if (bits == 0 && format.singleValuePalette) {
            val value = buffer.readVarInt()
            if (format.dataLengthPrefix) buffer.skipBytes(buffer.readVarInt() * 8)
            return IntArray(entries) { value }
        }
        val direct = if (biomes) bits > 3 else bits > 8
        val palette = if (direct) {
            if (format.directPaletteLength) buffer.readVarInt()
            null
        } else {
            IntArray(buffer.readVarInt()) { buffer.readVarInt() }
        }
        val storageBits = if (!direct && !biomes) maxOf(4, bits) else bits
        val longs = if (format.dataLengthPrefix) buffer.readVarInt() else BitPacking.longCount(entries, storageBits, format.spanning)
        val data = LongArray(longs) { buffer.readLong() }
        val values = BitPacking.unpack(data, storageBits, entries, format.spanning)
        return if (palette == null) values else IntArray(entries) { palette[values[it]] }
    }

    private fun skipBlockEntities(buffer: ByteBuf) {
        repeat(buffer.readVarInt()) {
            if (format.era == Era.V1_18) {
                buffer.readByte()
                buffer.readShort()
                buffer.readVarInt()
            }
            buffer.readNbt(version)
        }
    }

    private fun skipLight(buffer: ByteBuf) {
        if (format.trustEdges) buffer.readBoolean()
        repeat(4) { buffer.readBitSet(version) }
        repeat(2) { repeat(buffer.readVarInt()) { buffer.skipBytes(buffer.readVarInt()) } }
    }

    private fun maskSections(mask: Int): List<Int> =
        (0 until format.sectionCount).filter { mask and (1 shl it) != 0 }.map { it + format.minSection }
}
