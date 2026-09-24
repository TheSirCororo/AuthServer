package ru.cororo.authserver.protocol

import io.netty.buffer.Unpooled
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import ru.cororo.authserver.protocol.buffer.Components
import ru.cororo.authserver.protocol.packet.*
import ru.cororo.authserver.protocol.packet.play.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketRegistryTest {
    private val registry = MinecraftPackets.registry

    private fun table(version: ProtocolVersion, state: ProtocolState, direction: PacketDirection) =
        registry.table(version, state, direction)

    @Test
    fun `every version has the core packets`() {
        for (version in ProtocolVersion.entries) {
            val play = table(version, ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
            for (type in listOf(JoinGamePacket::class.java, PlayerPositionPacket::class.java, ChunkDataPacket::class.java,
                ClientboundKeepAlivePacket::class.java, DisconnectPacket::class.java, SystemChatPacket::class.java,
                TitleTextPacket::class.java, ActionBarPacket::class.java, SpawnPositionPacket::class.java)) {
                assertTrue(play.supports(type), "$version lacks ${type.simpleName}")
            }
            val serverbound = table(version, ProtocolState.PLAY, PacketDirection.SERVERBOUND)
            assertTrue(serverbound.supports(ChatPacket::class.java), "$version lacks chat")
            assertTrue(serverbound.supports(MovePositionPacket::class.java), "$version lacks movement")
        }
    }

    @Test
    fun `feature availability follows the game`() {
        val login = { version: ProtocolVersion -> table(version, ProtocolState.LOGIN, PacketDirection.CLIENTBOUND) }
        assertFalse(login(ProtocolVersion.MINECRAFT_1_12_2).supports(LoginPluginRequestPacket::class.java))
        assertTrue(login(ProtocolVersion.MINECRAFT_1_13).supports(LoginPluginRequestPacket::class.java))
        assertNull(table(ProtocolVersion.MINECRAFT_1_20, ProtocolState.CONFIGURATION, PacketDirection.CLIENTBOUND)
            .id(RegistryDataPacket::class.java))
        assertFalse(table(ProtocolVersion.MINECRAFT_1_8, ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
            .supports(BossBarPacket::class.java))
        assertFalse(table(ProtocolVersion.MINECRAFT_1_8, ProtocolState.PLAY, PacketDirection.SERVERBOUND)
            .supports(TeleportConfirmPacket::class.java))
    }

    @Test
    fun `ids match independently verified 26_2 constants`() {
        val play = table(ProtocolVersion.MINECRAFT_26_2, ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
        assertEquals(0x31, play.id(JoinGamePacket::class.java))
        assertEquals(0x48, play.id(PlayerPositionPacket::class.java))
        assertEquals(0x71, play.id(SetTimePacket::class.java))
        assertEquals(0x61, play.id(SpawnPositionPacket::class.java))
        val configuration = table(ProtocolVersion.MINECRAFT_26_2, ProtocolState.CONFIGURATION, PacketDirection.CLIENTBOUND)
        assertEquals(0x07, configuration.id(RegistryDataPacket::class.java))
        assertEquals(0x0e, configuration.id(ClientboundKnownPacksPacket::class.java))
    }

    @Test
    fun `legacy multiplexed packets share ids`() {
        val play = table(ProtocolVersion.MINECRAFT_1_12_2, ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
        assertEquals(play.id(TitleTextPacket::class.java), play.id(ActionBarPacket::class.java))
        val old = table(ProtocolVersion.MINECRAFT_1_8, ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
        assertEquals(old.id(SystemChatPacket::class.java), old.id(ActionBarPacket::class.java))
    }

    @Test
    fun `decodable packets survive a round trip on every version`() {
        val component = Component.text("Hello ", NamedTextColor.GOLD).append(Component.text("world", TextColor.color(0x3366ff)))
        val samples: List<Pair<ProtocolState, Packet>> = listOf(
            ProtocolState.HANDSHAKE to HandshakePacket(47, "example.org\u0000data", 25565, HandshakePacket.Intent.LOGIN),
            ProtocolState.STATUS to StatusPingPacket(42),
            ProtocolState.LOGIN to LoginSuccessPacket(UUID.randomUUID(), "Steve"),
            ProtocolState.LOGIN to LoginStartPacket("Alex", null),
            ProtocolState.PLAY to ClientboundKeepAlivePacket(1234),
            ProtocolState.PLAY to DisconnectPacket(component),
            ProtocolState.PLAY to SystemChatPacket(Component.text("hi"), overlay = false),
            ProtocolState.PLAY to PlayerPositionPacket(1.5, 64.0, -3.25, 90f, 10f, 7),
            ProtocolState.PLAY to PlayerAbilitiesPacket(invulnerable = true, flyingSpeed = 0.05f, walkingSpeed = 0.1f),
            ProtocolState.PLAY to GameEventPacket(3, 2f),
            ProtocolState.PLAY to SpawnPositionPacket(-10, 70, 20),
            ProtocolState.PLAY to ChatPacket("/login secret"),
            ProtocolState.PLAY to MovePositionRotationPacket(1.0, 2.0, 3.0, 4f, 5f, true),
            ProtocolState.PLAY to ServerboundKeepAlivePacket(99),
        )
        for (version in ProtocolVersion.entries) {
            for ((state, packet) in samples) {
                val direction = if (packet is ClientboundPacket) PacketDirection.CLIENTBOUND else PacketDirection.SERVERBOUND
                val lookup = table(version, state, direction).lookup(packet) ?: continue
                val buffer = Unpooled.buffer()
                try {
                    lookup.second.encode(buffer, packet, version)
                    val codec = assertNotNull(table(version, state, direction).codec(lookup.first))
                    val decoded = codec.decode(buffer, version)
                    assertEquals(0, buffer.readableBytes(), "Trailing bytes for $packet on $version")
                    assertEquals(normalise(packet, version), decoded, "Round trip of ${packet.javaClass.simpleName} on $version")
                } finally {
                    buffer.release()
                }
            }
        }
    }

    /** Fields a version cannot represent come back with their defaults. */
    private fun normalise(packet: Packet, version: ProtocolVersion): Packet = when (packet) {
        is LoginStartPacket -> if (version >= ProtocolVersion.MINECRAFT_1_20_2) packet.copy(uuid = NIL_UUID) else packet
        is PlayerPositionPacket -> if (version < ProtocolVersion.MINECRAFT_1_9) packet.copy(teleportId = 0) else packet
        is DisconnectPacket -> if (version < ProtocolVersion.MINECRAFT_1_16) {
            // Pre-1.16 JSON has no RGB colours; the blue text degrades to the nearest named colour.
            DisconnectPacket(Components.fromJson(Components.toJson(packet.reason, version), version))
        } else packet
        else -> packet
    }
}

