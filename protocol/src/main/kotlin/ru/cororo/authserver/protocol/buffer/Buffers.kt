package ru.cororo.authserver.protocol.buffer

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.ByteBufOutputStream
import io.netty.handler.codec.DecoderException
import net.kyori.adventure.nbt.BinaryTag
import net.kyori.adventure.nbt.BinaryTagType
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.EndBinaryTag
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_14
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_26_3
import java.io.DataOutput
import java.util.BitSet
import java.util.UUID

const val MAX_STRING_LENGTH = 32767
private const val MAX_VAR_INT_BYTES = 5
private const val MAX_VAR_LONG_BYTES = 10

fun ByteBuf.readVarInt(): Int {
    var result = 0
    for (index in 0 until MAX_VAR_INT_BYTES) {
        val byte = readByte().toInt()
        result = result or ((byte and 0x7F) shl (7 * index))
        if (byte and 0x80 == 0) return result
    }
    throw DecoderException("VarInt is longer than $MAX_VAR_INT_BYTES bytes")
}

fun ByteBuf.writeVarInt(value: Int) {
    var remaining = value
    while (remaining and 0x7F.inv() != 0) {
        writeByte((remaining and 0x7F) or 0x80)
        remaining = remaining ushr 7
    }
    writeByte(remaining)
}

fun varIntSize(value: Int): Int {
    var remaining = value
    var size = 1
    while (remaining and 0x7F.inv() != 0) {
        remaining = remaining ushr 7
        size++
    }
    return size
}

fun ByteBuf.readVarLong(): Long {
    var result = 0L
    for (index in 0 until MAX_VAR_LONG_BYTES) {
        val byte = readByte().toInt()
        result = result or ((byte and 0x7F).toLong() shl (7 * index))
        if (byte and 0x80 == 0) return result
    }
    throw DecoderException("VarLong is longer than $MAX_VAR_LONG_BYTES bytes")
}

fun ByteBuf.writeVarLong(value: Long) {
    var remaining = value
    while (remaining and 0x7FL.inv() != 0L) {
        writeByte(((remaining and 0x7F) or 0x80).toInt())
        remaining = remaining ushr 7
    }
    writeByte(remaining.toInt())
}

/** Reads a UTF-8 string of at most [maxLength] UTF-16 code units, as the vanilla client does. */
fun ByteBuf.readString(maxLength: Int = MAX_STRING_LENGTH): String {
    val bytes = readVarInt()
    if (bytes < 0 || bytes > maxLength * 3) throw DecoderException("String byte length $bytes exceeds ${maxLength * 3}")
    if (bytes > readableBytes()) throw DecoderException("String length $bytes exceeds readable bytes")
    val value = toString(readerIndex(), bytes, Charsets.UTF_8)
    skipBytes(bytes)
    if (value.length > maxLength) throw DecoderException("String length ${value.length} exceeds $maxLength")
    return value
}

fun ByteBuf.writeString(value: String, maxLength: Int = MAX_STRING_LENGTH) {
    require(value.length <= maxLength) { "String length ${value.length} exceeds $maxLength" }
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeVarInt(bytes.size)
    writeBytes(bytes)
}

fun ByteBuf.readByteArray(maxLength: Int = readableBytes()): ByteArray {
    val length = readVarInt()
    if (length < 0 || length > maxLength || length > readableBytes()) throw DecoderException("Invalid byte array length $length")
    return ByteArray(length).also(::readBytes)
}

fun ByteBuf.writeByteArray(bytes: ByteArray) {
    writeVarInt(bytes.size)
    writeBytes(bytes)
}

/** Reads every remaining byte, e.g. the tail of a plugin message. */
fun ByteBuf.readRemainingBytes(maxLength: Int = Int.MAX_VALUE): ByteArray {
    if (readableBytes() > maxLength) throw DecoderException("Payload of ${readableBytes()} bytes exceeds $maxLength")
    return ByteArray(readableBytes()).also(::readBytes)
}

fun ByteBuf.readUuid(): UUID = UUID(readLong(), readLong())

fun ByteBuf.writeUuid(uuid: UUID) {
    writeLong(uuid.mostSignificantBits)
    writeLong(uuid.leastSignificantBits)
}

/** Namespaced identifier such as `minecraft:overworld`. */
fun ByteBuf.readIdentifier(): String = readString(MAX_STRING_LENGTH)

fun ByteBuf.writeIdentifier(identifier: String) = writeString(identifier)

inline fun <T> ByteBuf.readList(maxSize: Int = readableBytes(), read: ByteBuf.() -> T): List<T> {
    val size = readVarInt()
    if (size < 0 || size > maxSize) throw DecoderException("List size $size exceeds $maxSize")
    return List(size) { read() }
}

inline fun <T> ByteBuf.writeList(values: Collection<T>, write: ByteBuf.(T) -> Unit) {
    writeVarInt(values.size)
    values.forEach { write(it) }
}

inline fun <T : Any> ByteBuf.readOptional(read: ByteBuf.() -> T): T? = if (readBoolean()) read() else null

inline fun <T : Any> ByteBuf.writeOptional(value: T?, write: ByteBuf.(T) -> Unit) {
    writeBoolean(value != null)
    if (value != null) write(value)
}

/** Bit sets are long arrays until 26.3 and byte arrays since. */
fun ByteBuf.readBitSet(version: ProtocolVersion): BitSet =
    if (version >= MINECRAFT_26_3) BitSet.valueOf(readByteArray())
    else BitSet.valueOf(LongArray(readVarInt().also { checkLength(it, 8) }) { readLong() })

fun ByteBuf.writeBitSet(bits: BitSet, version: ProtocolVersion) {
    if (version >= MINECRAFT_26_3) {
        writeByteArray(bits.toByteArray())
        return
    }
    val longs = bits.toLongArray()
    writeVarInt(longs.size)
    longs.forEach(::writeLong)
}

fun ByteBuf.writeLongArray(values: LongArray) {
    writeVarInt(values.size)
    values.forEach(::writeLong)
}

fun ByteBuf.readLongArray(): LongArray = LongArray(readVarInt().also { checkLength(it, 8) }) { readLong() }

/** Block position packed into a long; the layout changed from XYZ to XZY in 1.14. */
fun ByteBuf.writeBlockPosition(x: Int, y: Int, z: Int, version: ProtocolVersion) {
    val packed = if (version >= MINECRAFT_1_14) {
        ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
    } else {
        ((x.toLong() and 0x3FFFFFF) shl 38) or ((y.toLong() and 0xFFF) shl 26) or (z.toLong() and 0x3FFFFFF)
    }
    writeLong(packed)
}

/** Returns `(x, y, z)`. */
fun ByteBuf.readBlockPosition(version: ProtocolVersion): Triple<Int, Int, Int> {
    val packed = readLong()
    val x = (packed shr 38).toInt()
    return if (version >= MINECRAFT_1_14) {
        Triple(x, (packed shl 52 shr 52).toInt(), (packed shl 26 shr 38).toInt())
    } else {
        Triple(x, (packed shl 26 shr 52).toInt(), (packed shl 38 shr 38).toInt())
    }
}

/**
 * Writes network NBT. Before 1.20.2 the root tag carries an (empty) name; since then it is nameless.
 * Any tag type may be the root since 1.20.2 (used for text components).
 */
fun ByteBuf.writeNbt(tag: BinaryTag, version: ProtocolVersion) {
    ByteBufOutputStream(this).use { output ->
        output.writeByte(tag.type().id().toInt())
        if (version < MINECRAFT_1_20_2) output.writeShort(0)
        tag.writePayload(output)
    }
}

fun ByteBuf.readNbt(version: ProtocolVersion): BinaryTag {
    ByteBufInputStream(this).use { input ->
        val typeId = input.readByte()
        if (typeId.toInt() == 0) return EndBinaryTag.endBinaryTag()
        if (version < MINECRAFT_1_20_2) input.skipBytes(input.readUnsignedShort())
        return tagType(typeId).read(input)
    }
}

fun ByteBuf.writeCompound(tag: CompoundBinaryTag, version: ProtocolVersion) = writeNbt(tag, version)

fun ByteBuf.readCompound(version: ProtocolVersion): CompoundBinaryTag =
    readNbt(version) as? CompoundBinaryTag ?: throw DecoderException("Expected a compound NBT tag")

private fun checkLength(length: Int, elementSize: Int) {
    if (length < 0 || length.toLong() * elementSize > Int.MAX_VALUE) throw DecoderException("Invalid array length $length")
}

@Suppress("UNCHECKED_CAST")
private fun BinaryTag.writePayload(output: DataOutput) = (type() as BinaryTagType<BinaryTag>).write(this, output)

/** Explicit lookup: Adventure's own `binaryTagType(id)` only works once [BinaryTagTypes] happens to be initialised. */
private fun tagType(id: Byte): BinaryTagType<out BinaryTag> = when (id.toInt()) {
    1 -> BinaryTagTypes.BYTE
    2 -> BinaryTagTypes.SHORT
    3 -> BinaryTagTypes.INT
    4 -> BinaryTagTypes.LONG
    5 -> BinaryTagTypes.FLOAT
    6 -> BinaryTagTypes.DOUBLE
    7 -> BinaryTagTypes.BYTE_ARRAY
    8 -> BinaryTagTypes.STRING
    9 -> BinaryTagTypes.LIST
    10 -> BinaryTagTypes.COMPOUND
    11 -> BinaryTagTypes.INT_ARRAY
    12 -> BinaryTagTypes.LONG_ARRAY
    else -> throw DecoderException("Unknown NBT tag type $id")
}
