package ru.cororo.authserver.util

import net.kyori.adventure.nbt.*

fun nbtCompound(builder: CompoundBinaryTag.Builder.() -> Unit) = CompoundBinaryTag.builder().apply(builder).build()

fun nbtCompound(name: String, builder: CompoundBinaryTag.Builder.() -> Unit) =
    CompoundBinaryTag.builder().put(name, nbtCompound(builder)).build()

fun CompoundBinaryTag.Builder.putNbtCompound(name: String, builder: CompoundBinaryTag.Builder.() -> Unit) =
    put(nbtCompound(name, builder))

inline fun <reified T : Number> CompoundBinaryTag.Builder.put(name: String, number: T) = when (T::class) {
    Int::class -> putInt(name, number.toInt())
    Float::class -> putFloat(name, number.toFloat())
    Double::class -> putDouble(name, number.toDouble())
    Long::class -> putLong(name, number.toLong())
    Short::class -> putShort(name, number.toShort())
    Byte::class -> putByte(name, number.toByte())
    else -> putInt(name, number.toInt())
}

fun CompoundBinaryTag.Builder.put(name: String, string: String) = putString(name, string)

fun CompoundBinaryTag.Builder.putNbtList(name: String, builder: ListBinaryTag.Builder<BinaryTag>.() -> Unit) = put(name, ListBinaryTag.builder().apply(builder).build())

fun ListBinaryTag.Builder<BinaryTag>.addNbtCompound(name: String, builder: CompoundBinaryTag.Builder.() -> Unit) = add(nbtCompound(name, builder))

fun ListBinaryTag.Builder<BinaryTag>.addNbtCompound(builder: CompoundBinaryTag.Builder.() -> Unit) = add(nbtCompound(builder))