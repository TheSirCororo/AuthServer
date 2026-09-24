package ru.cororo.authserver.world.loader

import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.IntBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.LongArrayBinaryTag
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.world.encode.BitPacking
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.DeflaterOutputStream
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class MapLoaderTest {
    @TempDir
    lateinit var directory: Path

    private fun state(name: String) = assertNotNull(GameData.blocks.parse(name))

    private fun write(name: String, tag: CompoundBinaryTag, rootName: String = ""): Path =
        directory.resolve(name).also {
            BinaryTagIO.writer().writeNamed(java.util.Map.entry(rootName, tag), it, BinaryTagIO.Compression.GZIP)
        }

    private fun varInts(values: IntArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach { value ->
            var remaining = value
            while (remaining and 0x7F.inv() != 0) {
                write((remaining and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
            write(remaining)
        }
    }.toByteArray()

    @Test
    fun `sponge v2 schematic with old block names`() {
        // 2x1x2: stone, grass (pre-1.20.3 name), air, oak stairs facing east.
        val palette = CompoundBinaryTag.builder()
            .putInt("minecraft:stone", 0).putInt("minecraft:grass", 1).putInt("minecraft:air", 2)
            .putInt("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", 3).build()
        val schematic = CompoundBinaryTag.builder().putInt("Version", 2).putInt("DataVersion", 3465)
            .putShort("Width", 2).putShort("Height", 1).putShort("Length", 2)
            .put("Palette", palette).putByteArray("BlockData", varInts(intArrayOf(0, 1, 2, 3))).build()
        val world = MapLoader.load(write("lobby.schem", schematic, "Schematic"), Placement(10, 64, 20))
        assertEquals(state("stone"), world.getBlock(10, 64, 20))
        assertEquals(state("short_grass"), world.getBlock(11, 64, 20))
        assertEquals(0, world.getBlock(10, 64, 21))
        assertEquals(state("oak_stairs[facing=east]"), world.getBlock(11, 64, 21))
    }

    @Test
    fun `sponge v3 schematic`() {
        val blocks = CompoundBinaryTag.builder()
            .put("Palette", CompoundBinaryTag.builder().putInt("minecraft:glass", 0).build())
            .putByteArray("Data", varInts(intArrayOf(0, 0))).build()
        val schematic = CompoundBinaryTag.builder().putInt("Version", 3).putShort("Width", 1).putShort("Height", 2)
            .putShort("Length", 1).put("Blocks", blocks).build()
        val world = MapLoader.load(write("v3.schem", CompoundBinaryTag.builder().put("Schematic", schematic).build()))
        assertEquals(state("glass"), world.getBlock(0, 1, 0))
    }

    @Test
    fun `mcedit schematic uses legacy ids`() {
        val schematic = CompoundBinaryTag.builder().putShort("Width", 2).putShort("Height", 1).putShort("Length", 1)
            .putString("Materials", "Alpha").putByteArray("Blocks", byteArrayOf(35, 1))
            .putByteArray("Data", byteArrayOf(14, 1)).build()
        val world = MapLoader.load(write("old.schematic", schematic, "Schematic"))
        assertEquals(state("red_wool"), world.getBlock(0, 0, 0))
        assertEquals(state("granite"), world.getBlock(1, 0, 0))
    }

    @Test
    fun `vanilla structure file`() {
        val palette = ListBinaryTag.from(listOf(
            CompoundBinaryTag.builder().putString("Name", "minecraft:gold_block").build(),
            CompoundBinaryTag.builder().putString("Name", "minecraft:oak_log")
                .put("Properties", CompoundBinaryTag.builder().putString("axis", "x").build()).build(),
        ))
        val blocks = ListBinaryTag.from(listOf(
            CompoundBinaryTag.builder().put("pos", ListBinaryTag.from(listOf(0, 0, 0).map(IntBinaryTag::intBinaryTag))).putInt("state", 0).build(),
            CompoundBinaryTag.builder().put("pos", ListBinaryTag.from(listOf(0, 3, 1).map(IntBinaryTag::intBinaryTag))).putInt("state", 1).build(),
        ))
        val structure = CompoundBinaryTag.builder().put("palette", palette).put("blocks", blocks).build()
        val world = MapLoader.load(write("house.nbt", structure))
        assertEquals(state("gold_block"), world.getBlock(0, 0, 0))
        assertEquals(state("oak_log[axis=x]"), world.getBlock(0, 3, 1))
    }

    @Test
    fun `anvil region with modern chunks`() {
        val stone = CompoundBinaryTag.builder().putString("Name", "minecraft:stone").build()
        val air = CompoundBinaryTag.builder().putString("Name", "minecraft:air").build()
        // Bottom layer of the section is stone, the rest air.
        val indices = IntArray(4096) { if (it < 256) 1 else 0 }
        val section = CompoundBinaryTag.builder().putByte("Y", 4)
            .put("block_states", CompoundBinaryTag.builder().put("palette", ListBinaryTag.from(listOf(air, stone)))
                .put("data", LongArrayBinaryTag.longArrayBinaryTag(*BitPacking.pack(indices, 4, false))).build()).build()
        val chunk = CompoundBinaryTag.builder().putInt("DataVersion", 3955).putInt("xPos", 1).putInt("zPos", 2)
            .put("sections", ListBinaryTag.from(listOf(section))).build()
        val region = directory.resolve("world/region").createDirectories().resolve("r.0.0.mca")
        writeRegion(region, 1 + 2 * 32, chunk)
        val world = MapLoader.load(directory.resolve("world"))
        assertEquals(state("stone"), world.getBlock(16 + 5, 64, 32 + 7))
        assertEquals(0, world.getBlock(16 + 5, 65, 32 + 7))
    }

    private fun writeRegion(path: Path, index: Int, chunk: CompoundBinaryTag) {
        val nbt = ByteArrayOutputStream()
        DeflaterOutputStream(nbt).use { BinaryTagIO.writer().write(chunk, it) }
        val payload = nbt.toByteArray()
        DataOutputStream(Files.newOutputStream(path)).use { output ->
            repeat(1024) { output.writeInt(if (it == index) (2 shl 8) or 1 else 0) }
            repeat(1024) { output.writeInt(0) }
            output.writeInt(payload.size + 1)
            output.writeByte(2)
            output.write(payload)
            output.write(ByteArray(4096 - (payload.size + 5) % 4096))
        }
    }
}
