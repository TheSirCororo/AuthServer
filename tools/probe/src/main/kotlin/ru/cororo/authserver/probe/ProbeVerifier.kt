package ru.cororo.authserver.probe

import io.netty.buffer.Unpooled
import ru.cororo.authserver.protocol.MinecraftPackets
import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.PacketDirection
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.packet.*
import ru.cororo.authserver.protocol.packet.play.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks recorded vanilla frames against our codecs: every frame with a registered ID must decode without
 * leftovers, and packets whose model keeps every field must re-encode to the identical bytes.
 */
class ProbeVerifier(private val version: ProtocolVersion, private val directory: Path) {
    /** Models that carry the complete wire content, so re-encoding must be byte-exact. */
    private val lossless = setOf(
        ClientboundKeepAlivePacket::class, PlayerAbilitiesPacket::class, GameEventPacket::class,
        ChunkCacheCenterPacket::class, ChunkCacheRadiusPacket::class, SpawnPositionPacket::class,
        PlayerPositionPacket::class, PongResponsePacket::class, FeatureFlagsPacket::class,
        ClientboundKnownPacksPacket::class, SetCompressionPacket::class, PingPacket::class,
        ChunkDataPacket::class, LightUpdatePacket::class, RegistryDataPacket::class, UpdateTagsPacket::class,
        JoinGamePacket::class, LoginSuccessPacket::class, ContainerContentPacket::class, ContainerSlotPacket::class,
        SetHeldSlotPacket::class, CloseContainerPacket::class,
    )

    fun verify(): List<String> {
        val problems = mutableListOf<String>()
        val seen = sortedSetOf<String>()
        for (name in Files.readAllLines(directory.resolve("index.txt")).filter(String::isNotBlank)) {
            val (_, stateKey, idHex) = name.removeSuffix(".bin").split('_')
            val state = ProtocolState.valueOf(stateKey.uppercase())
            val id = idHex.toInt(16)
            val body = Files.readAllBytes(directory.resolve(name))
            val codec = MinecraftPackets.registry.table(version, state, PacketDirection.CLIENTBOUND).codec(id) ?: continue
            val buffer = Unpooled.wrappedBuffer(body)
            val packet = try {
                codec.decode(buffer, version)
            } catch (_: UnsupportedOperationException) {
                continue // encode-only codec
            } catch (exception: Exception) {
                problems += "$name: ${codec.javaClass.enclosingClass?.simpleName} failed to decode: $exception"
                continue
            }
            seen += packet.javaClass.simpleName
            if (buffer.isReadable) {
                problems += "$name: ${packet.javaClass.simpleName} left ${buffer.readableBytes()} of ${body.size} bytes"
                continue
            }
            if (packet::class in lossless) {
                @Suppress("UNCHECKED_CAST")
                val reencoded = Unpooled.buffer().also { (codec as PacketCodec<Packet>).encode(it, packet, version) }
                val bytes = ByteArray(reencoded.readableBytes()).also(reencoded::readBytes)
                // NBT compounds may come back in another key order; that is only a problem if the content differs.
                val equivalent = bytes.contentEquals(body) || codec.decode(Unpooled.wrappedBuffer(bytes), version) == packet
                if (!equivalent) problems += "$name: ${packet.javaClass.simpleName} re-encodes differently: $packet"
            }
        }
        println("$version: decoded ${seen.joinToString()}")
        return problems
    }
}
