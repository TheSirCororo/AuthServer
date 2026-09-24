package ru.cororo.authserver.protocol.buffer

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.netty.buffer.ByteBuf
import net.kyori.adventure.nbt.BinaryTag
import net.kyori.adventure.nbt.ByteArrayBinaryTag
import net.kyori.adventure.nbt.ByteBinaryTag
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.DoubleBinaryTag
import net.kyori.adventure.nbt.FloatBinaryTag
import net.kyori.adventure.nbt.IntArrayBinaryTag
import net.kyori.adventure.nbt.IntBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.LongArrayBinaryTag
import net.kyori.adventure.nbt.LongBinaryTag
import net.kyori.adventure.nbt.NumberBinaryTag
import net.kyori.adventure.nbt.ShortBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.json.JSONOptions
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_3
import java.util.concurrent.ConcurrentHashMap

/**
 * Version-aware text component serialization.
 *
 * Components are JSON strings until 1.20.3 and network NBT afterwards. The JSON dialect itself changes too
 * (RGB colours in 1.16, snake_case events in 1.21.5); Adventure's data-version options cover those shifts.
 */
object Components {
    private val serializers = ConcurrentHashMap<ProtocolVersion, GsonComponentSerializer>()

    fun serializer(version: ProtocolVersion): GsonComponentSerializer = serializers.computeIfAbsent(version) {
        GsonComponentSerializer.builder().options(JSONOptions.byDataVersion().at(it.dataVersion)).build()
    }

    fun toJson(component: Component, version: ProtocolVersion): String = serializer(version).serialize(component)

    fun fromJson(json: String, version: ProtocolVersion): Component = serializer(version).deserialize(json)

    fun toNbt(component: Component, version: ProtocolVersion): BinaryTag =
        jsonToNbt(serializer(version).serializeToTree(component))

    fun fromNbt(tag: BinaryTag, version: ProtocolVersion): Component =
        serializer(version).deserializeFromTree(nbtToJson(tag))

    /** Mirrors vanilla NbtOps: numbers keep their type, booleans become bytes, mixed lists wrap entries as `{"": value}`. */
    fun jsonToNbt(element: JsonElement): BinaryTag = when (element) {
        is JsonObject -> CompoundBinaryTag.builder().apply {
            element.entrySet().forEach { (key, value) -> if (value !is JsonNull) put(key, jsonToNbt(value)) }
        }.build()
        is JsonArray -> {
            val tags = element.filterNot { it is JsonNull }.map(::jsonToNbt)
            val types = tags.map { it.type() }.toSet()
            if (types.size <= 1) ListBinaryTag.from(tags)
            else ListBinaryTag.from(tags.map { tag ->
                if (tag is CompoundBinaryTag && !(tag.size() == 1 && tag.get("") != null)) tag
                else CompoundBinaryTag.builder().put("", tag).build()
            })
        }
        is JsonPrimitive -> when {
            element.isBoolean -> ByteBinaryTag.byteBinaryTag(if (element.asBoolean) 1 else 0)
            element.isNumber -> numberToNbt(element.asNumber)
            else -> StringBinaryTag.stringBinaryTag(element.asString)
        }
        else -> throw IllegalArgumentException("Cannot convert $element to NBT")
    }

    fun nbtToJson(tag: BinaryTag): JsonElement = when (tag) {
        is CompoundBinaryTag -> {
            val wrapped = tag.get("")
            if (tag.size() == 1 && wrapped != null) nbtToJson(wrapped)
            else JsonObject().apply { tag.forEach { (key, value) -> add(key, nbtToJson(value)) } }
        }
        is ListBinaryTag -> JsonArray().apply { tag.forEach { add(nbtToJson(it)) } }
        // UUIDs in hover events are int arrays.
        is IntArrayBinaryTag -> JsonArray().apply { tag.value().forEach { add(it) } }
        is LongArrayBinaryTag -> JsonArray().apply { tag.value().forEach { add(it) } }
        is ByteArrayBinaryTag -> JsonArray().apply { tag.value().forEach { add(it) } }
        is StringBinaryTag -> JsonPrimitive(tag.value())
        // Text components only use bytes for booleans.
        is ByteBinaryTag -> JsonPrimitive(tag.value() != 0.toByte())
        is NumberBinaryTag -> JsonPrimitive(tag.numberValue())
        else -> throw IllegalArgumentException("Unsupported tag ${tag.type()} in text component")
    }

    private fun numberToNbt(number: Number): BinaryTag {
        val value = number.toDouble()
        return when {
            number is Float -> FloatBinaryTag.floatBinaryTag(number)
            number is Long -> LongBinaryTag.longBinaryTag(number)
            number is Short -> ShortBinaryTag.shortBinaryTag(number)
            value == Math.rint(value) && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE ->
                IntBinaryTag.intBinaryTag(value.toInt())
            else -> DoubleBinaryTag.doubleBinaryTag(value)
        }
    }
}

/** Writes a component in the form used by most play/configuration packets of [version]. */
fun ByteBuf.writeComponent(component: Component, version: ProtocolVersion) {
    if (version >= MINECRAFT_1_20_3) writeNbt(Components.toNbt(component, version), version)
    else writeString(Components.toJson(component, version), 262144)
}

fun ByteBuf.readComponent(version: ProtocolVersion): Component =
    if (version >= MINECRAFT_1_20_3) Components.fromNbt(readNbt(version), version)
    else Components.fromJson(readString(262144), version)

/** Writes a component as a JSON string regardless of version (login disconnect, status response). */
fun ByteBuf.writeJsonComponent(component: Component, version: ProtocolVersion) =
    writeString(Components.toJson(component, version), 262144)

fun ByteBuf.readJsonComponent(version: ProtocolVersion): Component =
    Components.fromJson(readString(262144), version)
