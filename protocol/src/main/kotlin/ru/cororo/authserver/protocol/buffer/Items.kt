package ru.cororo.authserver.protocol.buffer

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DecoderException
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_13
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_13_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_14
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_5
import ru.cororo.authserver.protocol.registry.RegistryIds

/**
 * An item stack in the client's terms: [id] is the client version's network item ID, and before 1.13
 * `id << 16 | damage`. Only a display name and lore are modelled, which is all the limbo needs.
 */
data class ItemStack(
    val id: Int,
    val count: Int = 1,
    val name: Component? = null,
    val lore: List<Component> = emptyList(),
)

private val legacy = LegacyComponentSerializer.legacySection()
private const val CUSTOM_NAME = "minecraft:custom_name"
private const val LORE = "minecraft:lore"

/** Writes a slot; `null` is an empty slot. */
fun ByteBuf.writeItem(item: ItemStack?, version: ProtocolVersion) {
    when {
        version >= MINECRAFT_1_20_5 -> writeComponentItem(item, version)
        version >= MINECRAFT_1_13_2 -> {
            writeBoolean(item != null)
            if (item == null) return
            writeVarInt(item.id)
            writeByte(item.count)
            writeItemNbt(item, version)
        }
        else -> {
            if (item == null) {
                writeShort(-1)
                return
            }
            // Before the flattening the damage value travels in its own field.
            writeShort(if (version < MINECRAFT_1_13) item.id shr 16 else item.id)
            writeByte(item.count)
            if (version < MINECRAFT_1_13) writeShort(item.id and 0xFFFF)
            writeItemNbt(item, version)
        }
    }
}

fun ByteBuf.readItem(version: ProtocolVersion): ItemStack? = when {
    version >= MINECRAFT_1_20_5 -> readComponentItem(version)
    version >= MINECRAFT_1_13_2 -> if (!readBoolean()) null else {
        val id = readVarInt()
        val count = readByte().toInt()
        readItemNbt(ItemStack(id, count), version)
    }
    else -> {
        val raw = readShort().toInt()
        if (raw < 0) null else {
            val count = readByte().toInt()
            val id = if (version < MINECRAFT_1_13) (raw shl 16) or (readShort().toInt() and 0xFFFF) else raw
            readItemNbt(ItemStack(id, count), version)
        }
    }
}

// ------------------------------------------------------------------------------------------------ NBT items (< 1.20.5)

/** `display.Name` is legacy text before 1.13 and JSON afterwards; `display.Lore` switched to JSON in 1.14. */
private fun ByteBuf.writeItemNbt(item: ItemStack, version: ProtocolVersion) {
    if (item.name == null && item.lore.isEmpty()) {
        writeByte(0) // TAG_End: no tag
        return
    }
    val display = CompoundBinaryTag.builder()
    item.name?.let { display.putString("Name", if (version < MINECRAFT_1_13) legacy.serialize(it) else Components.toJson(it, version)) }
    if (item.lore.isNotEmpty()) {
        display.put("Lore", ListBinaryTag.from(item.lore.map { line ->
            StringBinaryTag.stringBinaryTag(if (version < MINECRAFT_1_14) legacy.serialize(line) else Components.toJson(line, version))
        }))
    }
    writeCompound(CompoundBinaryTag.builder().put("display", display.build()).build(), version)
}

private fun ByteBuf.readItemNbt(item: ItemStack, version: ProtocolVersion): ItemStack {
    if (getByte(readerIndex()).toInt() == 0) {
        skipBytes(1)
        return item
    }
    val display = readCompound(version).getCompound("display")
    val name = display.get("Name")?.let { (it as StringBinaryTag).value() }
        ?.let { if (version < MINECRAFT_1_13) legacy.deserialize(it) else Components.fromJson(it, version) }
    val lore = display.getList("Lore", BinaryTagTypes.STRING).map { tag ->
        val text = (tag as StringBinaryTag).value()
        if (version < MINECRAFT_1_14) legacy.deserialize(text) else Components.fromJson(text, version)
    }
    return item.copy(name = name, lore = lore)
}

// ------------------------------------------------------------------------------------------------ component items (1.20.5+)

private fun ByteBuf.writeComponentItem(item: ItemStack?, version: ProtocolVersion) {
    if (item == null) {
        writeVarInt(0)
        return
    }
    writeVarInt(item.count)
    writeVarInt(item.id)
    val components = buildList {
        item.name?.let { add(RegistryIds.require(RegistryIds.DATA_COMPONENT_TYPE, CUSTOM_NAME, version) to it) }
    }
    val hasLore = item.lore.isNotEmpty()
    writeVarInt(components.size + if (hasLore) 1 else 0)
    writeVarInt(0) // removed components
    components.forEach { (type, name) ->
        writeVarInt(type)
        writeComponent(name, version)
    }
    if (hasLore) {
        writeVarInt(RegistryIds.require(RegistryIds.DATA_COMPONENT_TYPE, LORE, version))
        writeList(item.lore) { writeComponent(it, version) }
    }
}

private fun ByteBuf.readComponentItem(version: ProtocolVersion): ItemStack? {
    val count = readVarInt()
    if (count <= 0) return null
    var item = ItemStack(readVarInt(), count)
    val added = readVarInt()
    val removed = readVarInt()
    val customName = RegistryIds.id(RegistryIds.DATA_COMPONENT_TYPE, CUSTOM_NAME, version)
    val loreType = RegistryIds.id(RegistryIds.DATA_COMPONENT_TYPE, LORE, version)
    repeat(added) {
        when (val type = readVarInt()) {
            customName -> item = item.copy(name = readComponent(version))
            loreType -> item = item.copy(lore = readList(256) { readComponent(version) })
            else -> throw DecoderException("Unsupported item component $type")
        }
    }
    repeat(removed) { readVarInt() }
    return item
}
