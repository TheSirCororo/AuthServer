package ru.cororo.authserver.protocol.packet.handler

import ru.cororo.authserver.AuthServer
import ru.cororo.authserver.protocol.packet.clientbound.ClientboundLoginEncryptionPacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundLoginStartPacket
import ru.cororo.authserver.protocol.utils.generateVerifyToken
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.velocity.logger

object LoginStartHandler : PacketHandler<ServerboundLoginStartPacket> {
    override val packetClass = ServerboundLoginStartPacket::class.java

    override fun handle(session: MinecraftSession, packet: ServerboundLoginStartPacket) {
        logger.info("Start logging player ${packet.username} from ${session.address.address}")
        session.sendPacket(
            ClientboundLoginEncryptionPacket(
                "",
                AuthServer.publicKeyString,
                generateVerifyToken()
            )
        )
    }
}