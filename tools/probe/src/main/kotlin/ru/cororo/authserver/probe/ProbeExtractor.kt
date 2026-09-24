package ru.cororo.authserver.probe

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import ru.cororo.authserver.protocol.MinecraftPackets
import ru.cororo.authserver.protocol.PacketDirection
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_16
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_5
import ru.cororo.authserver.protocol.buffer.readIdentifier
import ru.cororo.authserver.protocol.buffer.readCompound
import ru.cororo.authserver.protocol.buffer.readOptional
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeVarInt
import ru.cororo.authserver.protocol.packet.ClientboundKnownPacksPacket
import ru.cororo.authserver.protocol.packet.FeatureFlagsPacket
import ru.cororo.authserver.protocol.packet.RegistryDataPacket
import ru.cororo.authserver.protocol.packet.UpdateTagsPacket
import ru.cororo.authserver.protocol.packet.play.JoinGamePacket
import ru.cororo.authserver.protocol.packet.play.SetTimePacket
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.GZIPOutputStream

/**
 * Turns a vanilla recording into the per-version game data the auth server replays:
 * registry codecs, registry data and tags exactly as vanilla sends them, plus dimension facts.
 */
class ProbeExtractor(private val version: ProtocolVersion, private val recording: Path, private val output: Path) {
    fun extract() {
        Files.createDirectories(output)
        val properties = Properties()
        val registries = mutableListOf<ByteArray>()
        var tags: ByteArray? = null
        for (name in Files.readAllLines(recording.resolve("index.txt")).filter(String::isNotBlank)) {
            val (_, stateKey, idHex) = name.removeSuffix(".bin").split('_')
            val state = ProtocolState.valueOf(stateKey.uppercase())
            val codec = MinecraftPackets.registry.table(version, state, PacketDirection.CLIENTBOUND).codec(idHex.toInt(16)) ?: continue
            val packet = runCatching { codec.decode(Unpooled.wrappedBuffer(Files.readAllBytes(recording.resolve(name))), version) }
                .getOrNull() ?: continue
            when (packet) {
                is RegistryDataPacket -> registries += packet.body
                is UpdateTagsPacket -> if (tags == null) tags = packet.body
                is FeatureFlagsPacket -> properties["features"] = packet.features.joinToString(",")
                is ClientboundKnownPacksPacket ->
                    properties["knownPacks"] = packet.packs.joinToString(",") { "${it.namespace}:${it.id}:${it.version}" }
                is JoinGamePacket -> if (!properties.containsKey("dimensionType")) extractJoin(packet, properties)
                is SetTimePacket -> if (version >= ProtocolVersion.MINECRAFT_26_1) properties.putIfAbsent("clockId", packet.clockId.toString())
                else -> Unit
            }
        }
        if (registries.isNotEmpty()) {
            write("registries.bin.gz", frames(registries))
            dimensionFromRegistryData(registries)?.let { applyDimension(it, properties) }
            biomeIdFromRegistryData(registries, "minecraft:plains")?.let { properties["plainsBiome"] = it.toString() }
        }
        tags?.let { write("tags.bin.gz", it) }
        properties.putIfAbsent("minY", "0")
        properties.putIfAbsent("height", "256")
        Files.newBufferedWriter(output.resolve("version.properties")).use { properties.store(it, "Generated from vanilla $version") }
        println("Extracted $version: ${properties.keys.sortedBy { it.toString() }}")
    }

    private fun extractJoin(packet: JoinGamePacket, properties: Properties) {
        val dimension = packet.dimension
        properties["dimensionType"] = dimension.type
        properties["dimensionTypeId"] = dimension.typeId.toString()
        dimension.registryCodec?.let { codec ->
            writeNbt("codec.nbt.gz", codec)
            findDimensionType(codec, dimension.type)?.let { applyDimension(it, properties) }
            biomeIdFromCodec(codec, "minecraft:plains")?.let { properties["plainsBiome"] = it.toString() }
        }
        dimension.typeElement?.let {
            writeNbt("dimension.nbt.gz", it)
            applyDimension(it, properties)
        }
    }

    /** Looks the joined dimension type up in a registry codec (1.16 list layout or 1.16.2+ registry layout). */
    private fun findDimensionType(codec: CompoundBinaryTag, key: String): CompoundBinaryTag? {
        val entries = if (version == MINECRAFT_1_16 || version == ProtocolVersion.MINECRAFT_1_16_1) {
            codec.getList("dimension")
        } else {
            codec.getCompound("minecraft:dimension_type").getList("value")
        }
        return entries.map { it as CompoundBinaryTag }.firstOrNull { it.getString("name") == key }
            ?.let { entry -> if (entry.get("element") != null) entry.getCompound("element") else entry }
    }

    /** 1.20.2 - 1.20.4 send one codec compound, 1.20.5+ one packet per registry. */
    private fun dimensionFromRegistryData(bodies: List<ByteArray>): CompoundBinaryTag? {
        for (body in bodies) {
            val buffer = Unpooled.wrappedBuffer(body)
            if (version < MINECRAFT_1_20_5) return findDimensionType(buffer.readCompound(version), "minecraft:overworld")
            if (buffer.readIdentifier() != "minecraft:dimension_type") continue
            repeat(buffer.readVarInt()) {
                val id = buffer.readIdentifier()
                val data = buffer.readOptional { readCompound(version) }
                if (id == "minecraft:overworld") return data
            }
        }
        return null
    }

    /** 1.16.2+ codecs list biomes with explicit network IDs; 1.16 still used hard-coded biome IDs. */
    private fun biomeIdFromCodec(codec: CompoundBinaryTag, key: String): Int? {
        val biomes = codec.getCompound("minecraft:worldgen/biome").getList("value")
        return biomes.map { it as CompoundBinaryTag }.firstOrNull { it.getString("name") == key }?.getInt("id")
    }

    private fun biomeIdFromRegistryData(bodies: List<ByteArray>, key: String): Int? {
        for (body in bodies) {
            val buffer = Unpooled.wrappedBuffer(body)
            if (version < MINECRAFT_1_20_5) return biomeIdFromCodec(buffer.readCompound(version), key)
            if (buffer.readIdentifier() != "minecraft:worldgen/biome") continue
            repeat(buffer.readVarInt()) { index ->
                val id = buffer.readIdentifier()
                buffer.readOptional { readCompound(version) }
                if (id == key) return index
            }
        }
        return null
    }

    private fun applyDimension(type: CompoundBinaryTag, properties: Properties) {
        properties["minY"] = type.getInt("min_y", 0).toString()
        properties["height"] = type.getInt("height", 256).toString()
    }

    private fun frames(bodies: List<ByteArray>): ByteArray {
        val buffer: ByteBuf = Unpooled.buffer()
        buffer.writeVarInt(bodies.size)
        bodies.forEach {
            buffer.writeVarInt(it.size)
            buffer.writeBytes(it)
        }
        return ByteArray(buffer.readableBytes()).also(buffer::readBytes)
    }

    private fun writeNbt(name: String, tag: CompoundBinaryTag) {
        BinaryTagIO.writer().write(tag, output.resolve(name), BinaryTagIO.Compression.GZIP)
    }

    private fun write(name: String, bytes: ByteArray) {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(bytes) }
        Files.write(output.resolve(name), compressed.toByteArray())
    }
}
