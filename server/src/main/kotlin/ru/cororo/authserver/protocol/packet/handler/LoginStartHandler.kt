package ru.cororo.authserver.protocol.packet.handler

import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.protocol.Protocolable
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.packet.clientbound.login.ClientboundLoginEncryptionPacket
import ru.cororo.authserver.protocol.packet.serverbound.login.ServerboundLoginStartPacket
import ru.cororo.authserver.util.generateVerifyToken
import ru.cororo.authserver.session.MinecraftSession

object LoginStartHandler : PacketListener<ServerboundLoginStartPacket> {
    override val packetClass = ServerboundLoginStartPacket::class.java

    override fun handle(packet: ServerboundLoginStartPacket, protocolable: Protocolable) {
        check(protocolable is MinecraftSession) { "Serverbound packet cannot be sent from server" }

        protocolable.username = packet.username
        protocolable.sendPacket(
            ClientboundLoginEncryptionPacket(
                "",
                AuthServerImpl.keys.first.encoded,
                generateVerifyToken(protocolable).toByteArray()
            )
        )
    }
}