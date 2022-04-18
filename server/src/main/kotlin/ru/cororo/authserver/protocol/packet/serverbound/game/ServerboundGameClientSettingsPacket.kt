package ru.cororo.authserver.protocol.packet.serverbound.game

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.readString
import ru.cororo.authserver.util.readVarInt

class ServerboundGameClientSettingsPacket(
    val locale: String,
    val viewDistance: Byte,
    val chatMode: Int,
    val chatColors: Boolean,
    val skinParts: UByte,
    val mainHand: Int,
    val textFiltering: Boolean,
    val allowServerListings: Boolean
) : Packet {
    override val bound = PacketBound.SERVER

    companion object : PacketCodec<ServerboundGameClientSettingsPacket> {
        override val packetClass = ServerboundGameClientSettingsPacket::class.java

        override fun write(output: ByteBuf, packet: ServerboundGameClientSettingsPacket) {

        }

        override fun read(input: ByteBuf): ServerboundGameClientSettingsPacket {
            return ServerboundGameClientSettingsPacket(
                input.readString(16),
                input.readByte(),
                input.readVarInt(),
                input.readBoolean(),
                input.readByte().toUByte(),
                input.readVarInt(),
                input.readBoolean(),
                input.readBoolean()
            )
        }

    }
}