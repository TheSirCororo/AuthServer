package ru.cororo.authserver.protocol.packet.handler

import ru.cororo.authserver.protocol.packet.serverbound.ServerboundLoginEncryptionResponsePacket
import ru.cororo.authserver.session.MinecraftSession

object LoginEncryption : PacketHandler<ServerboundLoginEncryptionResponsePacket> {
    override val packetClass = ServerboundLoginEncryptionResponsePacket::class.java

    override fun handle(session: MinecraftSession, packet: ServerboundLoginEncryptionResponsePacket) {

    }
}