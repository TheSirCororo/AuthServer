package ru.cororo.authserver.protocol.packet.play

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_11
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19_1
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19_3
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_5
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_21_5
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_26_3
import ru.cororo.authserver.protocol.ServerboundPacket
import ru.cororo.authserver.protocol.buffer.readString
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeString
import ru.cororo.authserver.protocol.buffer.writeVarInt

private fun chatLimit(version: ProtocolVersion) = if (version >= MINECRAFT_1_11) 256 else 100

/** Unsigned "last seen messages" update used by 1.19.3+ chat and command packets. */
private fun ByteBuf.writeEmptyLastSeen() {
    writeVarInt(0) // offset
    writeZero(3) // fixed 20-bit acknowledgement bitset
}

/** Confirms a [PlayerPositionPacket]. Since 26.3 the client also reports where it ended up. */
data class TeleportConfirmPacket(
    val teleportId: Int,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) : ServerboundPacket {
    companion object Codec : PacketCodec<TeleportConfirmPacket> {
        override fun encode(buffer: ByteBuf, packet: TeleportConfirmPacket, version: ProtocolVersion) {
            buffer.writeVarInt(packet.teleportId)
            if (version >= MINECRAFT_26_3) {
                buffer.writeDouble(packet.x)
                buffer.writeDouble(packet.y)
                buffer.writeDouble(packet.z)
                buffer.writeFloat(packet.yaw)
                buffer.writeFloat(packet.pitch)
            }
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): TeleportConfirmPacket {
            val id = buffer.readVarInt()
            if (version < MINECRAFT_26_3) return TeleportConfirmPacket(id)
            return TeleportConfirmPacket(id, buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(), buffer.readFloat())
        }
    }
}

/**
 * Chat message. Commands arrive here before 1.19. Signature data after the text is skipped:
 * the auth server never relays chat, so it has no use for it.
 */
data class ChatPacket(val message: String) : ServerboundPacket {
    companion object Codec : PacketCodec<ChatPacket> {
        override fun encode(buffer: ByteBuf, packet: ChatPacket, version: ProtocolVersion) {
            buffer.writeString(packet.message, chatLimit(version))
            if (version < MINECRAFT_1_19) return
            buffer.writeLong(System.currentTimeMillis())
            buffer.writeLong(0) // salt
            when {
                version >= MINECRAFT_1_19_3 -> {
                    buffer.writeBoolean(false) // no signature
                    buffer.writeEmptyLastSeen()
                    if (version >= MINECRAFT_1_21_5) buffer.writeByte(0) // checksum
                }
                else -> {
                    buffer.writeVarInt(0) // empty signature
                    buffer.writeBoolean(false) // not previewed
                    if (version >= MINECRAFT_1_19_1) {
                        buffer.writeVarInt(0) // last seen
                        buffer.writeBoolean(false) // last received
                    }
                }
            }
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): ChatPacket {
            val message = buffer.readString(chatLimit(version))
            buffer.skipBytes(buffer.readableBytes())
            return ChatPacket(message)
        }
    }
}

/** A command without the leading slash (1.19+). Covers both unsigned and signed command packets. */
data class ChatCommandPacket(val command: String) : ServerboundPacket {
    companion object Codec : PacketCodec<ChatCommandPacket> {
        override fun encode(buffer: ByteBuf, packet: ChatCommandPacket, version: ProtocolVersion) {
            buffer.writeString(packet.command)
            if (version >= MINECRAFT_1_20_5) return
            buffer.writeLong(System.currentTimeMillis())
            buffer.writeLong(0) // salt
            buffer.writeVarInt(0) // argument signatures
            if (version >= MINECRAFT_1_19_3) {
                buffer.writeEmptyLastSeen()
            } else {
                buffer.writeBoolean(false) // not previewed
                if (version >= MINECRAFT_1_19_1) {
                    buffer.writeVarInt(0)
                    buffer.writeBoolean(false)
                }
            }
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): ChatCommandPacket {
            val command = buffer.readString()
            buffer.skipBytes(buffer.readableBytes())
            return ChatCommandPacket(command)
        }
    }
}

/** A command with signed arguments (1.20.5+); signatures are skipped like in [ChatCommandPacket]. */
data class SignedChatCommandPacket(val command: String) : ServerboundPacket {
    companion object Codec : PacketCodec<SignedChatCommandPacket> {
        override fun encode(buffer: ByteBuf, packet: SignedChatCommandPacket, version: ProtocolVersion) =
            ChatCommandPacket.encode(buffer, ChatCommandPacket(packet.command), MINECRAFT_1_19_3)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            SignedChatCommandPacket(ChatCommandPacket.decode(buffer, version).command)
    }
}

/** Movement packets: a flags byte replaced the on-ground boolean in 1.21.2 (bit 0 is still on-ground). */
private fun ByteBuf.writeMovementFlags(onGround: Boolean) {
    writeByte(if (onGround) 1 else 0)
}

private fun ByteBuf.readMovementFlags(): Boolean = readByte().toInt() and 0x01 != 0

data class MovePositionPacket(val x: Double, val y: Double, val z: Double, val onGround: Boolean) : ServerboundPacket {
    companion object Codec : PacketCodec<MovePositionPacket> {
        override fun encode(buffer: ByteBuf, packet: MovePositionPacket, version: ProtocolVersion) {
            buffer.writeDouble(packet.x)
            buffer.writeDouble(packet.y)
            buffer.writeDouble(packet.z)
            buffer.writeMovementFlags(packet.onGround)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            MovePositionPacket(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readMovementFlags())
    }
}

data class MovePositionRotationPacket(
    val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val onGround: Boolean,
) : ServerboundPacket {
    companion object Codec : PacketCodec<MovePositionRotationPacket> {
        override fun encode(buffer: ByteBuf, packet: MovePositionRotationPacket, version: ProtocolVersion) {
            buffer.writeDouble(packet.x)
            buffer.writeDouble(packet.y)
            buffer.writeDouble(packet.z)
            buffer.writeFloat(packet.yaw)
            buffer.writeFloat(packet.pitch)
            buffer.writeMovementFlags(packet.onGround)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = MovePositionRotationPacket(
            buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(), buffer.readFloat(),
            buffer.readMovementFlags(),
        )
    }
}

data class MoveRotationPacket(val yaw: Float, val pitch: Float, val onGround: Boolean) : ServerboundPacket {
    companion object Codec : PacketCodec<MoveRotationPacket> {
        override fun encode(buffer: ByteBuf, packet: MoveRotationPacket, version: ProtocolVersion) {
            buffer.writeFloat(packet.yaw)
            buffer.writeFloat(packet.pitch)
            buffer.writeMovementFlags(packet.onGround)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            MoveRotationPacket(buffer.readFloat(), buffer.readFloat(), buffer.readMovementFlags())
    }
}

data class MoveStatusPacket(val onGround: Boolean) : ServerboundPacket {
    companion object Codec : PacketCodec<MoveStatusPacket> {
        override fun encode(buffer: ByteBuf, packet: MoveStatusPacket, version: ProtocolVersion) =
            buffer.writeMovementFlags(packet.onGround)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = MoveStatusPacket(buffer.readMovementFlags())
    }
}

/** Latency probe from the F3 debug screen (1.20.2+), answered with [PongResponsePacket]. */
data class PingRequestPacket(val payload: Long) : ServerboundPacket {
    companion object Codec : PacketCodec<PingRequestPacket> {
        override fun encode(buffer: ByteBuf, packet: PingRequestPacket, version: ProtocolVersion) {
            buffer.writeLong(packet.payload)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = PingRequestPacket(buffer.readLong())
    }
}

data class PongResponsePacket(val payload: Long) : ClientboundPacket {
    companion object Codec : PacketCodec<PongResponsePacket> {
        override fun encode(buffer: ByteBuf, packet: PongResponsePacket, version: ProtocolVersion) {
            buffer.writeLong(packet.payload)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = PongResponsePacket(buffer.readLong())
    }
}
