package ru.cororo.authserver.protocol.buffer

import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import ru.cororo.authserver.protocol.ProtocolVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BuffersTest {
    @Test
    fun `var ints round trip including negatives`() {
        val buffer = Unpooled.buffer()
        val values = listOf(0, 1, 127, 128, 255, 25565, 2097151, Int.MAX_VALUE, -1, Int.MIN_VALUE)
        values.forEach(buffer::writeVarInt)
        values.forEach { assertEquals(it, buffer.readVarInt()) }
        assertEquals(5, varIntSize(-1))
        assertEquals(1, varIntSize(127))
    }

    @Test
    fun `var longs round trip`() {
        val buffer = Unpooled.buffer()
        listOf(0L, 1L, Long.MAX_VALUE, -1L, Long.MIN_VALUE).forEach {
            buffer.writeVarLong(it)
            assertEquals(it, buffer.readVarLong())
        }
    }

    @Test
    fun `oversized var int is rejected`() {
        val buffer = Unpooled.wrappedBuffer(byteArrayOf(-1, -1, -1, -1, -1, 1))
        assertFailsWith<DecoderException> { buffer.readVarInt() }
    }

    @Test
    fun `string length limits are enforced`() {
        val buffer = Unpooled.buffer()
        buffer.writeString("abcdefghijklmnopq")
        assertFailsWith<DecoderException> { buffer.readString(16) }
    }

    @Test
    fun `block positions pack per version`() {
        for (version in listOf(ProtocolVersion.MINECRAFT_1_8, ProtocolVersion.MINECRAFT_1_14, ProtocolVersion.LATEST)) {
            val buffer = Unpooled.buffer()
            buffer.writeBlockPosition(-30000000 + 1, -64, 29999999, version)
            assertEquals(Triple(-29999999, -64, 29999999), buffer.readBlockPosition(version))
        }
        val modern = Unpooled.buffer().apply { writeBlockPosition(0, 64, 0, ProtocolVersion.MINECRAFT_1_14) }
        assertEquals(64L, modern.readLong())
    }

    @Test
    fun `network nbt drops the root name since 1_20_2`() {
        val tag = CompoundBinaryTag.builder().putString("a", "b").build()
        val legacy = Unpooled.buffer().apply { writeNbt(tag, ProtocolVersion.MINECRAFT_1_20) }
        val modern = Unpooled.buffer().apply { writeNbt(tag, ProtocolVersion.MINECRAFT_1_20_2) }
        assertEquals(legacy.readableBytes() - 2, modern.readableBytes())
        assertEquals(tag, legacy.readCompound(ProtocolVersion.MINECRAFT_1_20))
        assertEquals(tag, modern.readCompound(ProtocolVersion.MINECRAFT_1_20_2))
    }

    @Test
    fun `components use nbt from 1_20_3 and survive round trips`() {
        val component = Component.text("Login", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text(" please"))
        for (version in ProtocolVersion.entries) {
            val buffer = Unpooled.buffer()
            buffer.writeComponent(component, version)
            assertEquals(component.compact(), buffer.readComponent(version).compact(), "Component on $version")
            assertEquals(0, buffer.readableBytes())
        }
        val plain = Components.toNbt(Component.text("x"), ProtocolVersion.MINECRAFT_1_20_3)
        assertIs<StringBinaryTag>(plain, "Plain text should use the compact string form")
    }
}
