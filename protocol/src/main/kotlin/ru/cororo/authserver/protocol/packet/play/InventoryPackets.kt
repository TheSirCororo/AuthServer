package ru.cororo.authserver.protocol.packet.play

import io.netty.buffer.ByteBuf
import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.ClientboundPacket
import io.netty.handler.codec.DecoderException
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_14
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_17_1
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_21_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_9
import ru.cororo.authserver.protocol.ServerboundPacket
import ru.cororo.authserver.protocol.buffer.ItemStack
import ru.cororo.authserver.protocol.buffer.readComponent
import ru.cororo.authserver.protocol.buffer.readItem
import ru.cororo.authserver.protocol.buffer.readJsonComponent
import ru.cororo.authserver.protocol.buffer.readString
import ru.cororo.authserver.protocol.buffer.readVarInt
import ru.cororo.authserver.protocol.buffer.writeComponent
import ru.cororo.authserver.protocol.buffer.writeItem
import ru.cororo.authserver.protocol.buffer.writeJsonComponent
import ru.cororo.authserver.protocol.buffer.writeString
import ru.cororo.authserver.protocol.buffer.writeVarInt
import ru.cororo.authserver.protocol.registry.RegistryIds

/** Window IDs are bytes until 1.21.2 and var ints afterwards; the player inventory is window 0. */
private fun ByteBuf.writeWindowId(id: Int, version: ProtocolVersion) {
    if (version >= MINECRAFT_1_21_2) writeVarInt(id) else writeByte(id)
}

private fun ByteBuf.readWindowId(version: ProtocolVersion): Int =
    if (version >= MINECRAFT_1_21_2) readVarInt() else readUnsignedByte().toInt()

/** Every slot of a window; [stateId] and [carried] (the cursor item) exist since 1.17.1. */
data class ContainerContentPacket(
    val windowId: Int,
    val items: List<ItemStack?>,
    val carried: ItemStack? = null,
    val stateId: Int = 0,
) : ClientboundPacket {
    companion object Codec : PacketCodec<ContainerContentPacket> {
        override fun encode(buffer: ByteBuf, packet: ContainerContentPacket, version: ProtocolVersion) {
            buffer.writeWindowId(packet.windowId, version)
            if (version >= MINECRAFT_1_17_1) {
                buffer.writeVarInt(packet.stateId)
                buffer.writeVarInt(packet.items.size)
            } else {
                buffer.writeShort(packet.items.size)
            }
            packet.items.forEach { buffer.writeItem(it, version) }
            if (version >= MINECRAFT_1_17_1) buffer.writeItem(packet.carried, version)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): ContainerContentPacket {
            val windowId = buffer.readWindowId(version)
            val stateId = if (version >= MINECRAFT_1_17_1) buffer.readVarInt() else 0
            val count = if (version >= MINECRAFT_1_17_1) buffer.readVarInt() else buffer.readShort().toInt()
            val items = List(count) { buffer.readItem(version) }
            val carried = if (version >= MINECRAFT_1_17_1) buffer.readItem(version) else null
            return ContainerContentPacket(windowId, items, carried, stateId)
        }
    }
}

/** One slot; window -1 slot -1 is the cursor. */
data class ContainerSlotPacket(val windowId: Int, val slot: Int, val item: ItemStack?, val stateId: Int = 0) : ClientboundPacket {
    companion object Codec : PacketCodec<ContainerSlotPacket> {
        override fun encode(buffer: ByteBuf, packet: ContainerSlotPacket, version: ProtocolVersion) {
            buffer.writeWindowId(packet.windowId, version)
            if (version >= MINECRAFT_1_17_1) buffer.writeVarInt(packet.stateId)
            buffer.writeShort(packet.slot)
            buffer.writeItem(packet.item, version)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): ContainerSlotPacket {
            val windowId = if (version >= MINECRAFT_1_21_2) buffer.readVarInt() else buffer.readByte().toInt()
            val stateId = if (version >= MINECRAFT_1_17_1) buffer.readVarInt() else 0
            return ContainerSlotPacket(windowId, buffer.readShort().toInt(), buffer.readItem(version), stateId)
        }
    }
}

/**
 * Opens a chest-like window of [rows] rows (1 - 6). Before 1.14 the type is a string plus a slot count;
 * afterwards it is the `generic_9xN` menu ID.
 */
data class OpenScreenPacket(val windowId: Int, val rows: Int, val title: Component) : ClientboundPacket {
    init {
        require(rows in 1..6) { "A chest window has 1 to 6 rows" }
    }

    companion object Codec : PacketCodec<OpenScreenPacket> {
        override fun encode(buffer: ByteBuf, packet: OpenScreenPacket, version: ProtocolVersion) {
            if (version >= MINECRAFT_1_14) {
                buffer.writeVarInt(packet.windowId)
                buffer.writeVarInt(RegistryIds.require(RegistryIds.MENU, "minecraft:generic_9x${packet.rows}", version))
                buffer.writeComponent(packet.title, version)
            } else {
                buffer.writeByte(packet.windowId)
                buffer.writeString("minecraft:chest", 32)
                buffer.writeJsonComponent(packet.title, version)
                buffer.writeByte(packet.rows * 9)
            }
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): OpenScreenPacket {
            if (version >= MINECRAFT_1_14) {
                val windowId = buffer.readVarInt()
                val type = buffer.readVarInt()
                val rows = (1..6).firstOrNull { RegistryIds.id(RegistryIds.MENU, "minecraft:generic_9x$it", version) == type }
                    ?: throw DecoderException("Menu type $type is not a chest")
                return OpenScreenPacket(windowId, rows, buffer.readComponent(version))
            }
            val windowId = buffer.readUnsignedByte().toInt()
            buffer.readString(32)
            val title = buffer.readJsonComponent(version)
            return OpenScreenPacket(windowId, buffer.readUnsignedByte() / 9, title)
        }
    }
}

data class CloseContainerPacket(val windowId: Int) : ClientboundPacket {
    companion object Codec : PacketCodec<CloseContainerPacket> {
        override fun encode(buffer: ByteBuf, packet: CloseContainerPacket, version: ProtocolVersion) =
            buffer.writeWindowId(packet.windowId, version)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = CloseContainerPacket(buffer.readWindowId(version))
    }
}

/** Selects hotbar slot [slot] (0 - 8). */
data class SetHeldSlotPacket(val slot: Int) : ClientboundPacket {
    companion object Codec : PacketCodec<SetHeldSlotPacket> {
        override fun encode(buffer: ByteBuf, packet: SetHeldSlotPacket, version: ProtocolVersion) {
            if (version >= MINECRAFT_1_21_2) buffer.writeVarInt(packet.slot) else buffer.writeByte(packet.slot)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) =
            SetHeldSlotPacket(if (version >= MINECRAFT_1_21_2) buffer.readVarInt() else buffer.readByte().toInt())
    }
}

// ------------------------------------------------------------------------------------------------ serverbound

/**
 * A click in a window. Only the window, slot and button are read: the auth server never lets items move and
 * answers every click by resending the window.
 */
data class ClickContainerPacket(val windowId: Int, val slot: Int, val button: Int) : ServerboundPacket {
    companion object Codec : PacketCodec<ClickContainerPacket> {
        override fun encode(buffer: ByteBuf, packet: ClickContainerPacket, version: ProtocolVersion) {
            buffer.writeWindowId(packet.windowId, version)
            if (version >= MINECRAFT_1_17_1) buffer.writeVarInt(0)
            buffer.writeShort(packet.slot)
            buffer.writeByte(packet.button)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): ClickContainerPacket {
            val windowId = buffer.readWindowId(version)
            if (version >= MINECRAFT_1_17_1) buffer.readVarInt() // state id
            val packet = ClickContainerPacket(windowId, buffer.readShort().toInt(), buffer.readByte().toInt())
            buffer.skipBytes(buffer.readableBytes())
            return packet
        }
    }
}

data class ServerboundCloseContainerPacket(val windowId: Int) : ServerboundPacket {
    companion object Codec : PacketCodec<ServerboundCloseContainerPacket> {
        override fun encode(buffer: ByteBuf, packet: ServerboundCloseContainerPacket, version: ProtocolVersion) =
            buffer.writeWindowId(packet.windowId, version)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = ServerboundCloseContainerPacket(buffer.readWindowId(version))
    }
}

/** Right click with an item in the air (1.9+). [hand] 0 is the main hand. */
data class UseItemPacket(val hand: Int) : ServerboundPacket {
    companion object Codec : PacketCodec<UseItemPacket> {
        override fun encode(buffer: ByteBuf, packet: UseItemPacket, version: ProtocolVersion) = buffer.writeVarInt(packet.hand)

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): UseItemPacket {
            val packet = UseItemPacket(buffer.readVarInt())
            buffer.skipBytes(buffer.readableBytes()) // sequence, rotation
            return packet
        }
    }
}

/**
 * Right click on a block. In 1.8 this packet ("Player Block Placement") is also how using an item in the air
 * arrives; it has no hand. The hand moved from after the position to first in 1.14.
 */
data class UseItemOnPacket(val hand: Int) : ServerboundPacket {
    companion object Codec : PacketCodec<UseItemOnPacket> {
        override fun encode(buffer: ByteBuf, packet: UseItemOnPacket, version: ProtocolVersion) {
            if (version >= MINECRAFT_1_14) {
                buffer.writeVarInt(packet.hand)
                buffer.writeLong(0)
                buffer.writeVarInt(0)
            } else {
                buffer.writeLong(0)
                if (version >= MINECRAFT_1_9) {
                    buffer.writeVarInt(0)
                    buffer.writeVarInt(packet.hand)
                }
            }
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion): UseItemOnPacket {
            val hand = when {
                version >= MINECRAFT_1_14 -> buffer.readVarInt()
                version >= MINECRAFT_1_9 -> {
                    buffer.readLong()
                    buffer.readVarInt()
                    buffer.readVarInt()
                }
                else -> 0
            }
            buffer.skipBytes(buffer.readableBytes())
            return UseItemOnPacket(hand)
        }
    }
}

/** The client selected hotbar slot [slot]. */
data class SetCarriedItemPacket(val slot: Int) : ServerboundPacket {
    companion object Codec : PacketCodec<SetCarriedItemPacket> {
        override fun encode(buffer: ByteBuf, packet: SetCarriedItemPacket, version: ProtocolVersion) {
            buffer.writeShort(packet.slot)
        }

        override fun decode(buffer: ByteBuf, version: ProtocolVersion) = SetCarriedItemPacket(buffer.readShort().toInt())
    }
}
