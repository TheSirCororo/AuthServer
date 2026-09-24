package ru.cororo.authserver.protocol.packet.play

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.buffer.readVarInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JoinGamePacketTest {
    @Test
    fun `26_3 game modes are var ints and the previous one is optional`() {
        // A vanilla 26.3 server's join packet for a new player in a flat world (no previous game mode).
        val buffer = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(VANILLA_26_3))
        val packet = JoinGamePacket.decode(buffer, ProtocolVersion.MINECRAFT_26_3)
        assertEquals(0, buffer.readableBytes())
        assertEquals(GameMode.SURVIVAL, packet.gameMode)
        assertNull(packet.previousGameMode)
        assertEquals(true, packet.isFlat)
        assertEquals(-63, packet.seaLevel)

        val encoded = Unpooled.buffer().also { JoinGamePacket.encode(it, packet, ProtocolVersion.MINECRAFT_26_3) }
        assertEquals(VANILLA_26_3, ByteBufUtil.hexDump(encoded))

        val creative = packet.copy(gameMode = GameMode.SPECTATOR, previousGameMode = GameMode.CREATIVE)
        val decoded = Unpooled.buffer().also { JoinGamePacket.encode(it, creative, ProtocolVersion.MINECRAFT_26_3) }
        assertEquals(creative.previousGameMode, JoinGamePacket.decode(decoded, ProtocolVersion.MINECRAFT_26_3).previousGameMode)
    }

    @Test
    fun `before 26_3 game modes are bytes and no previous one is -1`() {
        val packet = JoinGamePacket(entityId = 1, gameMode = GameMode.ADVENTURE, dimension = DimensionInfo())
        val buffer = Unpooled.buffer().also { JoinGamePacket.encode(it, packet, ProtocolVersion.MINECRAFT_26_2) }
        val decoded = JoinGamePacket.decode(buffer.copy(), ProtocolVersion.MINECRAFT_26_2)
        assertEquals(GameMode.ADVENTURE, decoded.gameMode)
        assertNull(decoded.previousGameMode)
        // entity id, hardcore, worlds, max players ... the previous game mode byte follows the game mode byte.
        buffer.skipBytes(4 + 1)
        repeat(buffer.readVarInt()) { buffer.skipBytes(buffer.readVarInt()) }
        repeat(3) { buffer.readVarInt() }
        buffer.skipBytes(3)
        buffer.readVarInt()
        buffer.skipBytes(buffer.readVarInt() + 8)
        assertEquals(GameMode.ADVENTURE.id.toByte(), buffer.readByte())
        assertEquals((-1).toByte(), buffer.readByte())
    }

    private companion object {
        const val VANILLA_26_3 = "000000010003136d696e6563726166743a6f766572776f726c64116d696e6563726166743a7468655f656e64146d696e6563726166743a7468655f6e657468657204030300010000136d696e6563726166743a6f766572776f726c643ca2ba85b554cdfd000000010000c1ffffff0f0000"
    }
}
