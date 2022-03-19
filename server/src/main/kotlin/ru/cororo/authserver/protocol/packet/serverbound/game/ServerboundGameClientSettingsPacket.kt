package ru.cororo.authserver.protocol.packet.serverbound.game

import io.ktor.utils.io.core.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.utils.readBoolean
import ru.cororo.authserver.protocol.utils.readString
import ru.cororo.authserver.protocol.utils.readVarInt

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

        override fun write(output: Output, packet: ServerboundGameClientSettingsPacket) {

        }

        @OptIn(ExperimentalUnsignedTypes::class)
        override fun read(input: Input): ServerboundGameClientSettingsPacket {
            return ServerboundGameClientSettingsPacket(
                input.readString(16),
                input.readByte(),
                input.readVarInt(),
                input.readBoolean(),
                input.readUByte(),
                input.readVarInt(),
                input.readBoolean(),
                input.readBoolean()
            )
        }

    }
}