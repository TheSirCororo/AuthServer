package ru.cororo.authserver.world.encode

import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.packet.play.ChunkDataPacket
import ru.cororo.authserver.protocol.packet.play.LightUpdatePacket
import ru.cororo.authserver.world.Chunk
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChunkCodecTest {
    private val blocks = GameData.blocks

    private fun fixture(version: ProtocolVersion): ByteArray? =
        javaClass.getResourceAsStream("/vanilla-chunks/${version.protocol}/chunk.bin.gz")?.let { GZIPInputStream(it).use { s -> s.readBytes() } }

    private fun clientState(version: ProtocolVersion, state: String) = GameData.version(version).blockState(assertNotNull(blocks.parse(state)))

    /** A default superflat column: bedrock, dirt, dirt, grass block at the bottom of the world. */
    @Test
    fun `vanilla superflat chunks decode on every version`() {
        val failures = ProtocolVersion.entries.mapNotNull { version ->
            runCatching {
                val packet = assertNotNull(fixture(version), "Missing fixture")
                val sections = ChunkDecoder(version).decode(packet.copyOfRange(8, packet.size))
                val bottom = GameData.version(version).minY
                val column = (0..3).map { y -> sections.getValue((bottom + y) shr 4)[((bottom + y) and 15) shl 8] }
                val expected = listOf("bedrock", "dirt", "dirt", "grass_block").map { clientState(version, it) }
                assertEquals(expected, column, "Superflat column")
            }.exceptionOrNull()?.let { "$version: $it" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `encoded chunks decode to the same blocks on every version`() {
        val random = Random(42)
        val palette = listOf("stone", "oak_planks", "glass", "cherry_stairs[facing=north]", "red_concrete", "air")
            .map { assertNotNull(blocks.parse(it)) }
        val chunk = Chunk(3, -7)
        repeat(3000) { chunk[random.nextInt(16), random.nextInt(0, 80), random.nextInt(16)] = palette.random(random) }
        // A section with more than 256 distinct states forces the direct (global) palette.
        for (index in 0 until 4096) chunk[index and 15, 96 + (index shr 8), (index shr 4) and 15] = 1 + index % 2000
        for (version in ProtocolVersion.entries) {
            val data = GameData.version(version)
            val packets = ChunkEncoder(version).encode(chunk)
            val packet = packets.filterIsInstance<ChunkDataPacket>().single()
            assertEquals(3 to -7, packet.x to packet.z)
            val decoded = ChunkDecoder(version).decode(packet.body)
            for (sectionY in (data.minY shr 4) until ((data.minY + data.height) shr 4)) {
                val section = chunk.section(sectionY)
                val states = decoded[sectionY]
                if (section == null || section.isEmpty) {
                    assertTrue(states == null || states.all { it == data.blockState(0) }, "$version section $sectionY should be empty")
                    continue
                }
                val expected = section.states().map(data::blockState)
                assertEquals(expected, assertNotNull(states, "$version section $sectionY missing").toList(), "$version section $sectionY")
            }
            assertEquals(version >= ProtocolVersion.MINECRAFT_1_14 && version < ProtocolVersion.MINECRAFT_1_18, packets.size == 2)
        }
    }

    @Test
    fun `light packets match vanilla layout`() {
        val lightVersions = ProtocolVersion.entries.filter { it >= ProtocolVersion.MINECRAFT_1_14 && it < ProtocolVersion.MINECRAFT_1_18 }
        for (version in lightVersions) {
            val vanilla = javaClass.getResourceAsStream("/vanilla-chunks/${version.protocol}/light.bin.gz")
                ?.let { GZIPInputStream(it).use { stream -> stream.readBytes() } }
            val decoder = ChunkDecoder(version)
            // Vanilla bodies start with the chunk coordinates as two var ints.
            vanilla?.let { decoder.decodeLightPacket(it.copyOfRange(varIntPrefix(it), it.size)) }
            val ours = ChunkEncoder(version).encode(Chunk(0, 0)).filterIsInstance<LightUpdatePacket>().single()
            assertEquals(18, decoder.decodeLightPacket(ours.body), "Sky light sections on $version")
        }
    }

    private fun varIntPrefix(bytes: ByteArray): Int {
        var offset = 0
        repeat(2) {
            do {
                val byte = bytes[offset++].toInt()
            } while (byte and 0x80 != 0)
        }
        return offset
    }
}
