package ru.cororo.authserver.gamedata

import ru.cororo.authserver.protocol.ProtocolVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameDataTest {
    private val blocks = GameData.blocks

    @Test
    fun `every version has complete tables`() {
        for (version in ProtocolVersion.entries) {
            val data = GameData.version(version)
            assertTrue(data.height in 256..4064 && data.height % 16 == 0, "$version height ${data.height}")
            // Touching every canonical state proves the mapping covers the whole canonical space.
            (0 until blocks.size).forEach { data.blockState(it) }
            GameData.items.indices.forEach { data.item(it) }
            if (version >= ProtocolVersion.MINECRAFT_1_20_2) assertTrue(data.registries.isNotEmpty(), "$version registries")
            if (version >= ProtocolVersion.MINECRAFT_1_16 && version < ProtocolVersion.MINECRAFT_1_20_2) {
                assertNotNull(data.dimension.registryCodec, "$version codec")
            }
        }
    }

    @Test
    fun `lenient parsing fills defaults and upgrades old names`() {
        val stone = assertNotNull(blocks.parse("stone"))
        assertEquals("minecraft:stone", blocks.state(stone))
        val stairs = assertNotNull(blocks.parse("minecraft:oak_stairs[facing=west]"))
        assertEquals("minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]", blocks.state(stairs))
        assertEquals("minecraft:short_grass", blocks.state(assertNotNull(blocks.parse("minecraft:grass"))))
        assertEquals("minecraft:dirt_path", blocks.state(assertNotNull(blocks.parse("grass_path"))))
        assertNull(blocks.parse("minecraft:not_a_block"))
    }

    @Test
    fun `legacy ids resolve`() {
        assertEquals("minecraft:granite", blocks.state(blocks.legacy(1, 1)))
        assertEquals("minecraft:red_wool", blocks.state(blocks.legacy(35, 14)))
        assertEquals(BlockStates.AIR, blocks.legacy(4000, 0))
    }

    @Test
    fun `new blocks degrade to similar old ones`() {
        val cherryStairs = assertNotNull(blocks.parse("cherry_stairs[facing=east]"))
        val legacy = GameData.version(ProtocolVersion.MINECRAFT_1_8).blockState(cherryStairs)
        assertEquals(53, legacy shr 4, "oak stairs")
        val modern = GameData.version(ProtocolVersion.MINECRAFT_1_20).blockState(cherryStairs)
        assertNotEquals(GameData.version(ProtocolVersion.MINECRAFT_1_20).blockState(assertNotNull(blocks.parse("oak_stairs[facing=east]"))), modern)
        val concrete = assertNotNull(blocks.parse("red_concrete"))
        assertEquals((35 shl 4) or 14, GameData.version(ProtocolVersion.MINECRAFT_1_8).blockState(concrete), "red wool")
    }

    @Test
    fun `items map across the flattening`() {
        val sign = assertNotNull(GameData.item("oak_sign"))
        assertEquals(323 shl 16, GameData.version(ProtocolVersion.MINECRAFT_1_12_2).item(sign))
        val granite = assertNotNull(GameData.item("granite"))
        assertEquals((1 shl 16) or 1, GameData.version(ProtocolVersion.MINECRAFT_1_8).item(granite))
    }
}
