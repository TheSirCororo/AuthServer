package ru.cororo.authserver.protocol.packet.handler

import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundHandshakePacket
import ru.cororo.authserver.session.MinecraftSession

object HandshakePacketHandler : PacketHandler<ServerboundHandshakePacket> {
    override val packetClass = ServerboundHandshakePacket::class.java

    override fun handle(session: MinecraftSession, packet: ServerboundHandshakePacket) {
        session.protocol.state = MinecraftProtocol.ProtocolState[packet.nextState]
    }
}