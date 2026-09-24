package ru.cororo.authserver.protocol.packet.play

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_17
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_21_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_21_9
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_26_1
import ru.cororo.authserver.protocol.buffer.readBlockPosition
import ru.cororo.authserver.protocol.buffer.readIdentifier
import ru.cororo.authserver.protocol.buffer.readRemainingBytes
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.readVarLong
import ru.cororo.authserver.protocol.buffer.writeBlockPosition
import ru.cororo.authserver.protocol.buffer.writeIdentifier
import ru.cororo.authserver.protocol.buffer.writeVarInt
import ru.cororo.authserver.protocol.buffer.writeVarLong

/**
 * Chunk column. Everything after the coordinates is version-specific and produced by the world module,
 * see `ChunkEncoder`; [body] is only valid for the version it was encoded for.
 */
class ChunkDataPacket(val x: Int, val z: Int, val body: ByteArray) : ClientboundPacket {
    companion object Codec : PacketCodec<ChunkDataPacket> {
        override fun encode(buffer: ByteBuf, packet: ChunkDataPacket, version: ProtocolVersion) {
            buffer.writeInt(packet.x)
            buffer.writeInt(packet.z)
            buffer.writeBytes(packet.body)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            ChunkDataPacket(buffer.readInt(), buffer.readInt(), buffer.readRemainingBytes())
    }
}

/** Separate light data (1.14 - 1.17.1); from 1.18 light is part of [ChunkDataPacket]. */
class LightUpdatePacket(val x: Int, val z: Int, val body: ByteArray) : ClientboundPacket {
    companion object Codec : PacketCodec<LightUpdatePacket> {
        override fun encode(buffer: ByteBuf, packet: LightUpdatePacket, version: ProtocolVersion) {
            buffer.writeVarInt(packet.x)
            buffer.writeVarInt(packet.z)
            buffer.writeBytes(packet.body)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            LightUpdatePacket(buffer.readVarInt(), buffer.readVarInt(), buffer.readRemainingBytes())
    }
}

/** Chunk the client centres its view on (1.14+). */
data class ChunkCacheCenterPacket(val x: Int, val z: Int) : ClientboundPacket {
    companion object Codec : PacketCodec<ChunkCacheCenterPacket> {
        override fun encode(buffer: ByteBuf, packet: ChunkCacheCenterPacket, version: ProtocolVersion) {
            buffer.writeVarInt(packet.x)
            buffer.writeVarInt(packet.z)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = ChunkCacheCenterPacket(buffer.readVarInt(), buffer.readVarInt())
    }
}

data class ChunkCacheRadiusPacket(val radius: Int) : ClientboundPacket {
    companion object Codec : PacketCodec<ChunkCacheRadiusPacket> {
        override fun encode(buffer: ByteBuf, packet: ChunkCacheRadiusPacket, version: ProtocolVersion) =
            buffer.writeVarInt(packet.radius)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = ChunkCacheRadiusPacket(buffer.readVarInt())
    }
}

/** World spawn (compass target and respawn point). */
data class SpawnPositionPacket(
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val dimension: String = "minecraft:overworld",
) : ClientboundPacket {
    companion object Codec : PacketCodec<SpawnPositionPacket> {
        override fun encode(buffer: ByteBuf, packet: SpawnPositionPacket, version: ProtocolVersion) {
            if (version >= MINECRAFT_1_21_9) buffer.writeIdentifier(packet.dimension)
            buffer.writeBlockPosition(packet.x, packet.y, packet.z, version)
            if (version >= MINECRAFT_1_17) buffer.writeFloat(packet.yaw)
            if (version >= MINECRAFT_1_21_9) buffer.writeFloat(packet.pitch)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): SpawnPositionPacket {
            val dimension = if (version >= MINECRAFT_1_21_9) buffer.readIdentifier() else "minecraft:overworld"
            val (x, y, z) = buffer.readBlockPosition(version)
            val yaw = if (version >= MINECRAFT_1_17) buffer.readFloat() else 0f
            val pitch = if (version >= MINECRAFT_1_21_9) buffer.readFloat() else 0f
            return SpawnPositionPacket(x, y, z, yaw, pitch, dimension)
        }
    }
}

/**
 * World time. Since 26.1 time is carried by world clocks; [clockId] is the network ID of the overworld clock
 * and a frozen clock has rate 0.
 */
data class SetTimePacket(
    val worldAge: Long,
    val timeOfDay: Long,
    val ticking: Boolean = false,
    val clockId: Int = 0,
) : ClientboundPacket {
    companion object Codec : PacketCodec<SetTimePacket> {
        override fun encode(buffer: ByteBuf, packet: SetTimePacket, version: ProtocolVersion) {
            buffer.writeLong(packet.worldAge)
            when {
                version >= MINECRAFT_26_1 -> {
                    buffer.writeVarInt(1)
                    buffer.writeVarInt(packet.clockId)
                    buffer.writeVarLong(packet.timeOfDay)
                    buffer.writeFloat(0f) // partial tick
                    buffer.writeFloat(if (packet.ticking) 1f else 0f)
                }
                version >= MINECRAFT_1_21_2 -> {
                    buffer.writeLong(packet.timeOfDay)
                    buffer.writeBoolean(packet.ticking)
                }
                // Negative time of day stops the daylight cycle on older clients.
                else -> buffer.writeLong(if (packet.ticking) packet.timeOfDay else -packet.timeOfDay.coerceAtLeast(1))
            }
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): SetTimePacket {
            val age = buffer.readLong()
            return when {
                version >= MINECRAFT_26_1 -> {
                    var clockId = 0
                    var time = 0L
                    var rate = 0f
                    repeat(buffer.readVarInt()) {
                        clockId = buffer.readVarInt()
                        time = buffer.readVarLong()
                        buffer.readFloat()
                        rate = buffer.readFloat()
                    }
                    SetTimePacket(age, time, rate != 0f, clockId)
                }
                version >= MINECRAFT_1_21_2 -> SetTimePacket(age, buffer.readLong(), buffer.readBoolean())
                else -> buffer.readLong().let { SetTimePacket(age, kotlin.math.abs(it), it >= 0) }
            }
        }
    }
}
