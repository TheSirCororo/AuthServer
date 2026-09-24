package ru.cororo.authserver.protocol.packet.play

import io.netty.buffer.ByteBuf
import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.ClientboundPacket
import ru.cororo.authserver.protocol.EncodeOnlyCodec
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_11
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_16
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_17
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_19_1
import ru.cororo.authserver.protocol.buffer.readComponent
import ru.cororo.authserver.protocol.buffer.readUuid
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeComponent
import ru.cororo.authserver.protocol.buffer.writeUuid
import ru.cororo.authserver.protocol.buffer.writeVarInt
import ru.cororo.authserver.protocol.packet.NIL_UUID
import java.util.UUID

private const val LEGACY_POSITION_SYSTEM = 1
private const val LEGACY_POSITION_ACTION_BAR = 2
private const val CHAT_TYPE_SYSTEM = 1
private const val CHAT_TYPE_GAME_INFO = 2

/** Pre-1.19 chat packet layout: component, position byte, and a sender UUID since 1.16. */
private fun ByteBuf.writeLegacyChat(message: Component, position: Int, version: ProtocolVersion) {
    writeComponent(message, version)
    writeByte(position)
    if (version >= MINECRAFT_1_16) writeUuid(NIL_UUID)
}

/** Server message in the chat window, or above the hotbar when [overlay] is set. */
data class SystemChatPacket(val message: Component, val overlay: Boolean = false) : ClientboundPacket {
    companion object Codec : PacketCodec<SystemChatPacket> {
        override fun encode(buffer: ByteBuf, packet: SystemChatPacket, version: ProtocolVersion) {
            when {
                version >= MINECRAFT_1_19_1 -> {
                    buffer.writeComponent(packet.message, version)
                    buffer.writeBoolean(packet.overlay)
                }
                version >= MINECRAFT_1_19 -> {
                    buffer.writeComponent(packet.message, version)
                    buffer.writeVarInt(if (packet.overlay) CHAT_TYPE_GAME_INFO else CHAT_TYPE_SYSTEM)
                }
                else -> buffer.writeLegacyChat(
                    packet.message, if (packet.overlay) LEGACY_POSITION_ACTION_BAR else LEGACY_POSITION_SYSTEM, version,
                )
            }
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): SystemChatPacket {
            val message = buffer.readComponent(version)
            val overlay = when {
                version >= MINECRAFT_1_19_1 -> buffer.readBoolean()
                version >= MINECRAFT_1_19 -> buffer.readVarInt() == CHAT_TYPE_GAME_INFO
                else -> (buffer.readByte().toInt() == LEGACY_POSITION_ACTION_BAR).also {
                    if (version >= MINECRAFT_1_16) buffer.readUuid()
                }
            }
            return SystemChatPacket(message, overlay)
        }
    }
}

/**
 * Titles. Before 1.17 one multiplexed packet carried every title operation; the action numbering shifted in 1.11
 * when the action bar joined it. These codecs only encode: the models share a single legacy ID.
 */
private enum class LegacyTitleAction(private val before111: Int?, private val since111: Int) {
    TITLE(0, 0),
    SUBTITLE(1, 1),
    ACTION_BAR(null, 2),
    TIMES(2, 3),
    CLEAR(3, 4),
    RESET(4, 5);

    fun id(version: ProtocolVersion): Int =
        if (version >= MINECRAFT_1_11) since111 else requireNotNull(before111) { "$name is not a title action before 1.11" }
}

private fun ByteBuf.writeLegacyTitle(action: LegacyTitleAction, version: ProtocolVersion) = writeVarInt(action.id(version))

data class TitleTextPacket(val text: Component) : ClientboundPacket {
    companion object Codec : EncodeOnlyCodec<TitleTextPacket> {
        override fun encode(buffer: ByteBuf, packet: TitleTextPacket, version: ProtocolVersion) {
            if (version < MINECRAFT_1_17) buffer.writeLegacyTitle(LegacyTitleAction.TITLE, version)
            buffer.writeComponent(packet.text, version)
        }
    }
}

data class SubtitleTextPacket(val text: Component) : ClientboundPacket {
    companion object Codec : EncodeOnlyCodec<SubtitleTextPacket> {
        override fun encode(buffer: ByteBuf, packet: SubtitleTextPacket, version: ProtocolVersion) {
            if (version < MINECRAFT_1_17) buffer.writeLegacyTitle(LegacyTitleAction.SUBTITLE, version)
            buffer.writeComponent(packet.text, version)
        }
    }
}

/** Title fade-in, stay and fade-out durations in ticks. */
data class TitleTimesPacket(val fadeIn: Int, val stay: Int, val fadeOut: Int) : ClientboundPacket {
    companion object Codec : EncodeOnlyCodec<TitleTimesPacket> {
        override fun encode(buffer: ByteBuf, packet: TitleTimesPacket, version: ProtocolVersion) {
            if (version < MINECRAFT_1_17) buffer.writeLegacyTitle(LegacyTitleAction.TIMES, version)
            buffer.writeInt(packet.fadeIn)
            buffer.writeInt(packet.stay)
            buffer.writeInt(packet.fadeOut)
        }
    }
}

/** Hides the current title; [reset] also forgets the subtitle and durations. */
data class ClearTitlesPacket(val reset: Boolean) : ClientboundPacket {
    companion object Codec : EncodeOnlyCodec<ClearTitlesPacket> {
        override fun encode(buffer: ByteBuf, packet: ClearTitlesPacket, version: ProtocolVersion) {
            if (version >= MINECRAFT_1_17) buffer.writeBoolean(packet.reset)
            else buffer.writeLegacyTitle(if (packet.reset) LegacyTitleAction.RESET else LegacyTitleAction.CLEAR, version)
        }
    }
}

/**
 * Text above the hotbar: a chat packet before 1.11, a title action until 1.17, a dedicated packet afterwards.
 */
data class ActionBarPacket(val text: Component) : ClientboundPacket {
    companion object Codec : EncodeOnlyCodec<ActionBarPacket> {
        override fun encode(buffer: ByteBuf, packet: ActionBarPacket, version: ProtocolVersion) = when {
            version >= MINECRAFT_1_17 -> buffer.writeComponent(packet.text, version)
            version >= MINECRAFT_1_11 -> {
                buffer.writeLegacyTitle(LegacyTitleAction.ACTION_BAR, version)
                buffer.writeComponent(packet.text, version)
            }
            else -> buffer.writeLegacyChat(packet.text, LEGACY_POSITION_ACTION_BAR, version)
        }
    }
}

data class TabListPacket(val header: Component, val footer: Component) : ClientboundPacket {
    companion object Codec : PacketCodec<TabListPacket> {
        override fun encode(buffer: ByteBuf, packet: TabListPacket, version: ProtocolVersion) {
            buffer.writeComponent(packet.header, version)
            buffer.writeComponent(packet.footer, version)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            TabListPacket(buffer.readComponent(version), buffer.readComponent(version))
    }
}

/** Boss bar operations (1.9+). */
data class BossBarPacket(val id: UUID, val operation: Operation) : ClientboundPacket {
    enum class Color { PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE }

    enum class Overlay { PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20 }

    sealed interface Operation {
        data class Add(val title: Component, val progress: Float, val color: Color, val overlay: Overlay) : Operation
        data object Remove : Operation
        data class UpdateProgress(val progress: Float) : Operation
        data class UpdateTitle(val title: Component) : Operation
    }

    companion object Codec : EncodeOnlyCodec<BossBarPacket> {
        override fun encode(buffer: ByteBuf, packet: BossBarPacket, version: ProtocolVersion) {
            buffer.writeUuid(packet.id)
            when (val operation = packet.operation) {
                is Operation.Add -> {
                    buffer.writeVarInt(0)
                    buffer.writeComponent(operation.title, version)
                    buffer.writeFloat(operation.progress)
                    buffer.writeVarInt(operation.color.ordinal)
                    buffer.writeVarInt(operation.overlay.ordinal)
                    buffer.writeByte(0) // flags: no darkening, music or fog
                }
                Operation.Remove -> buffer.writeVarInt(1)
                is Operation.UpdateProgress -> {
                    buffer.writeVarInt(2)
                    buffer.writeFloat(operation.progress)
                }
                is Operation.UpdateTitle -> {
                    buffer.writeVarInt(3)
                    buffer.writeComponent(operation.title, version)
                }
            }
        }
    }
}
