package ru.cororo.authserver.protocol.packet.clientbound.login

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.util.readString
import ru.cororo.authserver.util.writeString
import ru.cororo.authserver.util.writeUUID
import java.util.*

data class ClientboundLoginSuccessPacket(
    val uuid: UUID,
    val username: String
) : Packet {
    override val bound = PacketBound.CLIENT

    companion object : PacketCodec<ClientboundLoginSuccessPacket> {
        override val packetClass = ClientboundLoginSuccessPacket::class.java

        override fun write(output: ByteBuf, packet: ClientboundLoginSuccessPacket) {
            output.writeUUID(packet.uuid)
            output.writeString(packet.username)
        }

        override fun read(input: ByteBuf): ClientboundLoginSuccessPacket {
            return ClientboundLoginSuccessPacket(UUID.randomUUID(), input.readString())
        }
    }
}