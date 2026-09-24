package ru.cororo.authserver.protocol.packet.play

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_17
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19_4
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_21_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_9
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeVarInt

/** Teleports the player; the client must confirm [teleportId] (1.9+). All coordinates are absolute. */
data class PlayerPositionPacket(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val teleportId: Int = 0,
) : ClientboundPacket {
    companion object Codec : PacketCodec<PlayerPositionPacket> {
        override fun encode(buffer: ByteBuf, packet: PlayerPositionPacket, version: ProtocolVersion) {
            with(buffer) {
                if (version >= MINECRAFT_1_21_2) {
                    writeVarInt(packet.teleportId)
                    writeDouble(packet.x)
                    writeDouble(packet.y)
                    writeDouble(packet.z)
                    repeat(3) { writeDouble(0.0) } // velocity
                    writeFloat(packet.yaw)
                    writeFloat(packet.pitch)
                    writeInt(0) // no relative components
                } else {
                    writeDouble(packet.x)
                    writeDouble(packet.y)
                    writeDouble(packet.z)
                    writeFloat(packet.yaw)
                    writeFloat(packet.pitch)
                    writeByte(0)
                    if (version >= MINECRAFT_1_9) writeVarInt(packet.teleportId)
                    if (version >= MINECRAFT_1_17 && version < MINECRAFT_1_19_4) writeBoolean(false) // dismount vehicle
                }
            }
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): PlayerPositionPacket = with(buffer) {
            if (version >= MINECRAFT_1_21_2) {
                val id = readVarInt()
                val x = readDouble()
                val y = readDouble()
                val z = readDouble()
                repeat(3) { readDouble() }
                val yaw = readFloat()
                val pitch = readFloat()
                readInt()
                PlayerPositionPacket(x, y, z, yaw, pitch, id)
            } else {
                val packet = PlayerPositionPacket(readDouble(), readDouble(), readDouble(), readFloat(), readFloat())
                readByte()
                val id = if (version >= MINECRAFT_1_9) readVarInt() else 0
                if (version >= MINECRAFT_1_17 && version < MINECRAFT_1_19_4) readBoolean()
                packet.copy(teleportId = id)
            }
        }
    }
}

data class PlayerAbilitiesPacket(
    val invulnerable: Boolean = true,
    val flying: Boolean = false,
    val allowFlying: Boolean = false,
    val creativeMode: Boolean = false,
    val flyingSpeed: Float = 0.05f,
    val walkingSpeed: Float = 0.1f,
) : ClientboundPacket {
    companion object Codec : PacketCodec<PlayerAbilitiesPacket> {
        override fun encode(buffer: ByteBuf, packet: PlayerAbilitiesPacket, version: ProtocolVersion) {
            var flags = 0
            if (packet.invulnerable) flags = flags or 0x01
            if (packet.flying) flags = flags or 0x02
            if (packet.allowFlying) flags = flags or 0x04
            if (packet.creativeMode) flags = flags or 0x08
            buffer.writeByte(flags)
            buffer.writeFloat(packet.flyingSpeed)
            buffer.writeFloat(packet.walkingSpeed)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): PlayerAbilitiesPacket {
            val flags = buffer.readByte().toInt()
            return PlayerAbilitiesPacket(
                flags and 0x01 != 0, flags and 0x02 != 0, flags and 0x04 != 0, flags and 0x08 != 0,
                buffer.readFloat(), buffer.readFloat(),
            )
        }
    }
}

/** `Change Game State` / `Game Event`: a numbered event with a float argument. */
data class GameEventPacket(val event: Int, val value: Float = 0f) : ClientboundPacket {
    companion object Codec : PacketCodec<GameEventPacket> {
        /** Tells 1.20.3+ clients to close the loading screen once chunks around them are present. */
        const val START_WAITING_FOR_CHUNKS = 13
        const val CHANGE_GAME_MODE = 3

        override fun encode(buffer: ByteBuf, packet: GameEventPacket, version: ProtocolVersion) {
            buffer.writeByte(packet.event)
            buffer.writeFloat(packet.value)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            GameEventPacket(buffer.readUnsignedByte().toInt(), buffer.readFloat())
    }
}
