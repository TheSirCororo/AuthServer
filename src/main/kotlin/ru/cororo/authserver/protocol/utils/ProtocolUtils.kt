package ru.cororo.authserver.protocol.utils

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import net.benwoodworth.knbt.*
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Get from https://github.com/KarbonPowered/Karbon/blob/rewrite-v2/src/commonMain/kotlin/com/karbonpowered/network/IOUtil.kt
 */
suspend fun ByteReadChannel.readVarInt(): Int {
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

suspend fun ByteWriteChannel.writeVarInt(int: Int) {
    var value = int
    do {
        var temp = (value and 127).toByte()
        value = value ushr 7
        if (value != 0) {
            temp = temp or 128.toByte()
        }
        writeByte(temp)
    } while (value != 0)
}

fun Input.readVarInt(): Int {
    var numRead = 0
    var result = 0
    var read: Byte
    do {
        read = readByte()
        val value = (read and 127).toInt()
        result = result or (value shl 7 * numRead)
        numRead++
        if (numRead > 5) {
            throw IOException("VarInt is too big")
        }
    } while (read and 128.toByte() != 0.toByte())
    return result
}

fun Output.writeVarInt(i: Int) {
    var value = i
    do {
        var temp = (value and 127).toByte()
        value = value ushr 7
        if (value != 0) {
            temp = temp or 128.toByte()
        }
        writeByte(temp)
    } while (value != 0)
}

fun Output.writeBoolean(boolean: Boolean) = writeByte(if (boolean) 1.toByte() else 0.toByte())
fun Input.readBoolean(): Boolean = readByte() == 1.toByte()

const val DEFAULT_MAX_STRING_SIZE = 65536 // 64KiB

fun Input.readString(capacity: Int = DEFAULT_MAX_STRING_SIZE): String {
    val length = readVarInt()
    return readString(capacity, length)
}

fun Input.readString(capacity: Int, length: Int): String {
    check(length >= 0) { "Hot a negative-length string ($length)" }
    check(length <= capacity * 4) { "Bad string size (got $length, maximum is $capacity)" }
//    check(availableForRead >= length) {
//        "Trying to read a string that is too long (wanted $length, only have $availableForRead)"
//    }
    val string = String(readBytes(length))
    check(string.length <= capacity) { "Got a too-long string (got ${string.length}, max $capacity)" }
    return string
}

fun Output.writeString(string: String) {
    val bytes = string.toByteArray()
    writeVarInt(bytes.size)
    writeFully(bytes)
}

fun Output.writeUUID(uuid: UUID) {
    writeLong(uuid.mostSignificantBits)
    writeLong(uuid.leastSignificantBits)
}

fun Input.readUUID(): UUID {
    return UUID(readLong(), readLong())
}

fun Input.readByteArray(): ByteArray {
    val length: Int = readVarInt()
    return readBytes(length)
}

fun Output.writeByteArray(array: ByteArray) {
    writeVarInt(array.size)
    writeFully(array)
}

fun Output.writeStringArray(array: Array<String>) {
    writeVarInt(array.size)
    array.forEach {
        writeString(it)
    }
}

fun Output.writeNBT(nbt: NbtTag, name: String) {
    val nbtType = nbt.nbtType
    writeByte(nbtType.id)
    val bytes = name.toByteArray()
    writeShort(bytes.size.toShort())
    writeFully(bytes)
    writeCompoundContent(nbt, name)
}

private fun Output.writeCompoundContent(compound: NbtTag, name: String) {
    when (compound.nbtType) {
        NbtType.ByteTag -> writeByte(compound.nbtByte.value)
        NbtType.ByteArrayTag -> {
            val array = compound.nbtByteArray
            writeInt(array.size)
            array.forEach {
                writeByte(it)
            }
        }
        NbtType.CompoundTag -> {
            compound.nbtCompound.entries.forEach {
                writeCompoundContent(it.value, it.key)
            }
            writeByte(NbtType.EndTag.id)
        }
        NbtType.DoubleTag -> writeDouble(compound.nbtDouble.value)
        NbtType.FloatTag -> writeFloat(compound.nbtFloat.value)
        NbtType.IntArrayTag -> {
            val array = compound.nbtIntArray
            writeInt(array.size)
            array.forEach {
                writeInt(it)
            }
        }
        NbtType.IntTag -> writeInt(compound.nbtInt.value)
        NbtType.ListTag -> {
            val list = compound.nbtList
            writeByte(list.first().nbtType.id)
            writeInt(list.size)
            list.forEach {
                writeCompoundContent(it, name)
            }
        }
        NbtType.LongArrayTag -> {
            val array = compound.nbtLongArray
            writeInt(array.size)
            array.forEach {
                writeLong(it)
            }
        }
        NbtType.LongTag -> writeLong(compound.nbtLong.value)
        NbtType.ShortTag -> writeShort(compound.nbtShort.value)
        NbtType.StringTag -> {
            val stringNbt = compound.nbtString
            val bytes = stringNbt.value.toByteArray()
            writeShort(bytes.size.toShort())
            writeFully(bytes)
        }
        NbtType.EndTag -> throw IllegalArgumentException()
    }
}

sealed class NbtType(
    val id: Byte,
    val name: String
) {
    object EndTag : NbtType(0, "TAG_End")
    object ByteTag : NbtType(1, "TAG_Byte")
    object ShortTag : NbtType(2, "TAG_Short")
    object IntTag : NbtType(3, "TAG_Int")
    object LongTag : NbtType(4, "TAG_Long")
    object FloatTag : NbtType(5, "TAG_Float")
    object DoubleTag : NbtType(6, "TAG_Double")
    object ByteArrayTag : NbtType(7, "TAG_Byte_Array")
    object StringTag : NbtType(8, "TAG_String")
    object ListTag : NbtType(9, "TAG_List")
    object CompoundTag : NbtType(10, "TAG_Compound")
    object IntArrayTag : NbtType(11, "TAG_Int_Array")
    object LongArrayTag : NbtType(12, "TAG_Long_Array")
}

private val NbtTag.nbtType: NbtType
    get() =
        when (this) {
            is NbtByte -> NbtType.ByteTag
            is NbtShort -> NbtType.ShortTag
            is NbtInt -> NbtType.IntTag
            is NbtLong -> NbtType.LongTag
            is NbtFloat -> NbtType.FloatTag
            is NbtDouble -> NbtType.DoubleTag
            is NbtByteArray -> NbtType.ByteArrayTag
            is NbtString -> NbtType.StringTag
            is NbtList<*> -> NbtType.ListTag
            is NbtCompound -> NbtType.CompoundTag
            is NbtIntArray -> NbtType.IntArrayTag
            is NbtLongArray -> NbtType.LongArrayTag
        }