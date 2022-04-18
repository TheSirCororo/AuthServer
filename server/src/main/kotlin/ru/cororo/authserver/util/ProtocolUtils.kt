package ru.cororo.authserver.util

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.kyori.adventure.nbt.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files.readString
import java.nio.file.Files.writeString
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or

fun ByteBuf.readVarInt(): Int {
    var numRead = 0
    var result = 0
    var read: Byte
    do {
        read = readByte()
        val value = (read and 127).toInt()
        result = result or (value shl 7 * numRead)
        numRead++
        if (numRead > 5) {
            throw RuntimeException("VarInt is too big")
        }
    } while (read and 128.toByte() != 0.toByte())
    return result
}

fun ByteBuf.writeVarInt(int: Int) {
    var value = int
    do {
        var temp = (value and 127).toByte()
        value = value ushr 7
        if (value != 0) {
            temp = temp or 128.toByte()
        }
        writeByte(temp.toInt())
    } while (value != 0)
}

fun ByteBuf.readVarLong(): Long {
    var value: Long = 0
    var length = 0
    var currentByte: Byte

    while (true) {
        currentByte = readByte()
        value = value or ((currentByte and 0x7F).toLong() shl length * 7)
        length += 1
        if (length > 10) {
            throw RuntimeException("VarLong is too big")
        }
        if ((currentByte and 0x80.toByte()).toInt() != 0x80) {
            break
        }
    }
    return value
}

fun ByteBuf.writeVarLong(i: Long) {
    var value = i
    while (true) {
        if (value and 0x7F.inv() == 0L) {
            writeByte(value.toInt())
            return
        }
        writeByte((value and 0x7F or 0x80).toInt())
        // Note: >>> means that the sign bit is shifted with the rest of the number rather than being left alone
        value = value ushr 7
    }
}

const val DEFAULT_MAX_STRING_SIZE = 65536 // 64KiB

fun ByteBuf.readString(capacity: Int = DEFAULT_MAX_STRING_SIZE): String {
    val length = readVarInt()
    return readString(capacity, length)
}

fun ByteBuf.readString(capacity: Int, length: Int): String {
    check(length >= 0) { "Hot a negative-length string ($length)" }
    check(length <= capacity * 4) { "Bad string size (got $length, maximum is $capacity)" }
//    check(availableForRead >= length) {
//        "Trying to read a string that is too long (wanted $length, only have $availableForRead)"
//    }
    val string = String(Unpooled.copiedBuffer(readBytes(length)).array())
    check(string.length <= capacity) { "Got a too-long string (got ${string.length}, max $capacity)" }
    return string
}

fun ByteBuf.writeString(string: String) {
    val bytes = string.toByteArray()
    writeVarInt(bytes.size)
    writeBytes(bytes)
}

fun ByteBuf.writeUUID(uuid: UUID) {
    writeLong(uuid.mostSignificantBits)
    writeLong(uuid.leastSignificantBits)
}

fun ByteBuf.writeComponent(component: Component) {
    writeString(GsonComponentSerializer.gson().serialize(component))
}

fun ByteBuf.readComponent(): Component {
    return GsonComponentSerializer.gson().deserialize(readString())
}

fun ByteBuf.readUUID(): UUID {
    return UUID(readLong(), readLong())
}

fun ByteBuf.readByteArray(): ByteArray {
    val length: Int = readVarInt()
    return Unpooled.copiedBuffer(readBytes(length)).array()
}

fun ByteBuf.writeByteArray(array: ByteArray) {
    writeVarInt(array.size)
    writeBytes(array)
}

fun ByteBuf.writeStringArray(array: Array<String>) {
    writeVarInt(array.size)
    array.forEach {
        writeString(it)
    }
}

fun ByteBuf.writeNBT(nbt: CompoundBinaryTag) {
    val outputStream = ByteArrayOutputStream()
    BinaryTagIO.writer().write(nbt, outputStream)
    writeBytes(outputStream.toByteArray())
}

fun ByteBuf.writeNBT(nbt: MutableMap.MutableEntry<String, CompoundBinaryTag>) {
    val outputStream = ByteArrayOutputStream()
    BinaryTagIO.writer().writeNamed(nbt, outputStream)
    writeBytes(outputStream.toByteArray())
}

fun ByteBuf.writeTag(tag: BinaryTag) {
    writeTag("", tag)
}

fun ByteBuf.writeTag(name: String, tag: BinaryTag) {
    val bytes = ByteArrayOutputStream()
    val output = DataOutputStream(bytes)
    writeTag(name, tag, output)
    output.flush()
    writeBytes(bytes.toByteArray())
}

private fun writeTag(name: String, tag: BinaryTag, output: DataOutputStream) {
    val type = tag.type()
    val nameBytes: ByteArray = name.toByteArray(Charsets.UTF_8)
    if (type == BinaryTagTypes.END) {
        throw IOException("Named TAG_End not permitted.")
    }
    output.writeByte(type.id().toInt())
    output.writeShort(nameBytes.size)
    output.write(nameBytes)
    writeTagPayload(tag, output)
}

private fun writeTagPayload(tag: BinaryTag, output: DataOutputStream) {
    val type = tag.type()
    val bytes: ByteArray
    when (type) {
        BinaryTagTypes.BYTE -> output.writeByte((tag as ByteBinaryTag).value().toInt())
        BinaryTagTypes.SHORT -> output.writeShort((tag as ShortBinaryTag).value().toInt())
        BinaryTagTypes.INT -> output.writeInt((tag as IntBinaryTag).value())
        BinaryTagTypes.LONG -> output.writeLong((tag as LongBinaryTag).value())
        BinaryTagTypes.FLOAT -> output.writeFloat((tag as FloatBinaryTag).value())
        BinaryTagTypes.DOUBLE -> output.writeDouble((tag as DoubleBinaryTag).value())
        BinaryTagTypes.BYTE_ARRAY -> {
            bytes = (tag as ByteArrayBinaryTag).value()
            output.writeInt(bytes.size)
            output.write(bytes)
        }
        BinaryTagTypes.STRING -> {
            bytes = (tag as StringBinaryTag).value().toByteArray(Charsets.UTF_8)
            output.writeShort(bytes.size)
            output.write(bytes)
        }
        BinaryTagTypes.LIST -> {
            val listTag = tag as ListBinaryTag
            val tags = listTag.toList()
            output.writeByte(listTag.type().id().toInt())
            output.writeInt(tags.size)
            for (child in tags) {
                writeTagPayload(child, output)
            }
        }
        BinaryTagTypes.COMPOUND -> {
            val map: Map<String, BinaryTag> = (tag as CompoundBinaryTag).associate { it.key to it.value }
            for ((key, value) in map) {
                writeTag(key, value, output)
            }
            output.writeByte(0) // end tag
        }
        BinaryTagTypes.INT_ARRAY -> {
            val ints = (tag as IntArrayBinaryTag).value() as IntArray
            output.writeInt(ints.size)
            for (value in ints) {
                output.writeInt(value)
            }
        }
        BinaryTagTypes.LONG_ARRAY -> {
            val longs = (tag as LongArrayBinaryTag).value() as LongArray
            output.writeInt(longs.size)
            for (value in longs) {
                output.writeLong(value)
            }
        }
        else -> throw IOException("Invalid tag type: $type.")
    }
}