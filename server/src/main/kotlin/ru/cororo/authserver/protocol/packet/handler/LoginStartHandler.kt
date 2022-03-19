package ru.cororo.authserver.protocol.packet.handler

import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.Protocolable
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.packet.clientbound.ClientboundLoginEncryptionPacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundLoginStartPacket
import ru.cororo.authserver.protocol.utils.generateVerifyToken
import ru.cororo.authserver.session.MinecraftSession

object LoginStartHandler : PacketListener<ServerboundLoginStartPacket> {
    override val packetClass = ServerboundLoginStartPacket::class.java

    override fun handle(packet: ServerboundLoginStartPacket, protocolable: Protocolable) {
        check(protocolable is MinecraftSession) { "Serverbound packet cannot was sent from server" }

        protocolable.username = packet.username
        protocolable.protocol.state = MinecraftProtocol.ProtocolState.GAME
        protocolable.sendPacket(
            ClientboundLoginEncryptionPacket(
                "",
                ru.cororo.authserver.AuthServerImpl.keys.first.encoded,
                generateVerifyToken(protocolable).toByteArray()
            )
        )
    }
}