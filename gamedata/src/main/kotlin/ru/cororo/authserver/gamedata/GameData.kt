package ru.cororo.authserver.gamedata

import io.netty.buffer.Unpooled
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.packet.KnownPack
import ru.cororo.authserver.protocol.packet.play.DimensionInfo
import java.util.EnumMap
import java.util.Properties

/** Entry point to generated game data. Everything is loaded lazily and cached. */
object GameData {
    val blocks: BlockStates by lazy(BlockStates::load)

    /** Item names of the newest release, indexed by canonical item ID. */
    val items: List<String> by lazy { Resources.gzipLines("items.txt.gz") }

    private val itemIds: Map<String, Int> by lazy { items.withIndex().associate { (id, name) -> name to id } }
    private val versions = EnumMap<ProtocolVersion, VersionData>(ProtocolVersion::class.java)

    fun item(name: String): Int? = itemIds[if (':' in name) name else "minecraft:$name"]

    fun version(version: ProtocolVersion): VersionData = synchronized(versions) {
        versions.getOrPut(version) { VersionData(version) }
    }
}

/** Game data of one protocol version. */
class VersionData internal constructor(val version: ProtocolVersion) {
    private val directory = version.protocol.toString()
    private val properties = Properties().apply {
        Resources.require("$directory/version.properties").use { load(it) }
    }
    private val blockMapping: IntArray by lazy { Resources.versionedInts(version.protocol, "blocks") }
    private val itemMapping: IntArray by lazy { Resources.versionedInts(version.protocol, "items") }

    /** Lowest block Y and world height of the overworld as this version's client expects it. */
    val minY: Int = properties.getProperty("minY", "0").toInt()
    val height: Int = properties.getProperty("height", "256").toInt()

    /** Number of block states in the client's global palette (1.13+); earlier versions use 13-bit `id << 4 | meta`. */
    val blockStateCount: Int = properties.getProperty("blockStateCount", "8192").toInt()

    /** Network ID of the plains biome, used for the whole limbo world. */
    val plainsBiome: Int = properties.getProperty("plainsBiome", "1").toInt()

    /** Network ID of `brigadier:string` (1.19+). */
    val stringArgumentParser: Int = properties.getProperty("stringArgumentParser", "5").toInt()

    /** Network ID of the overworld clock (26.1+). */
    val clockId: Int = properties.getProperty("clockId", "0").toInt()

    val features: List<String> = properties.getProperty("features", "minecraft:vanilla").split(',')

    val knownPacks: List<KnownPack> = properties.getProperty("knownPacks").orEmpty().split(',')
        .filter(String::isNotBlank).map { pack ->
            val (namespace, id, packVersion) = pack.split(':', limit = 3)
            KnownPack(namespace, id, packVersion)
        }

    /** Registry data packet bodies captured from vanilla (1.20.2+), in sending order. */
    val registries: List<ByteArray> by lazy {
        val buffer = Unpooled.wrappedBuffer(Resources.gzipBytesOrNull("$directory/registries.bin.gz") ?: return@lazy emptyList())
        List(buffer.readVarInt()) { ByteArray(buffer.readVarInt()).also(buffer::readBytes) }
    }

    /** `Update Tags` body (1.13+), or `null` for versions without tags. */
    val tags: ByteArray? by lazy { Resources.gzipBytesOrNull("$directory/tags.bin.gz") }

    val dimension: DimensionInfo by lazy {
        DimensionInfo(
            type = properties.getProperty("dimensionType", "minecraft:overworld"),
            name = "minecraft:overworld",
            registryCodec = nbt("codec.nbt.gz"),
            typeElement = nbt("dimension.nbt.gz"),
            typeId = properties.getProperty("dimensionTypeId", "0").toInt(),
        )
    }

    /** The client's network block state for canonical [state]. */
    fun blockState(state: Int): Int = blockMapping[state]

    /**
     * The client's network item for canonical [item]. Before 1.13 the value is `id << 16 | damage`.
     */
    fun item(item: Int): Int = itemMapping[item]

    private fun nbt(name: String): CompoundBinaryTag? =
        Resources.open("$directory/$name")?.use { BinaryTagIO.reader().read(it, BinaryTagIO.Compression.GZIP) }
}
