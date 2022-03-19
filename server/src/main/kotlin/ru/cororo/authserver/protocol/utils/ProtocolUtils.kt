package ru.cororo.authserver.protocol.utils

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.encodeToByteArray
import net.benwoodworth.knbt.Nbt
import net.benwoodworth.knbt.NbtCompression
import net.benwoodworth.knbt.NbtTag
import net.benwoodworth.knbt.NbtVariant
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or

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
    var value = 0
    var length = 0
    var currentByte: Byte
    while (true) {
        currentByte = readByte()
        value = value or ((currentByte and 0x7F).toInt() shl length * 7)
        length += 1
        if (length > 5) {
            throw RuntimeException("VarInt is too big")
        }
        if ((currentByte and 0x80.toByte()).toInt() != 0x80) {
            break
        }
    }
    return value
}

fun Output.writeVarInt(i: Int) {
    var value = i
    while (true) {
        if (value and 0x7F.inv() == 0) {
            writeByte(value.toByte())
            return
        }

        writeByte((value and 0x7F or 0x80).toByte())
        value = value ushr 7
    }
}

fun Input.readVarLong(): Long {
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

fun Output.writeVarLong(i: Long) {
    var value = i
    while (true) {
        if (value and 0x7F.inv() == 0L) {
            writeByte(value.toByte())
            return
        }
        writeByte((value and 0x7F or 0x80).toByte())
        // Note: >>> means that the sign bit is shifted with the rest of the number rather than being left alone
        value = value ushr 7
    }
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

fun Output.writeNBT(nbt: NbtTag) {
    writeFully(Nbt { compression = NbtCompression.None; variant = NbtVariant.Java }.encodeToByteArray(nbt))
}