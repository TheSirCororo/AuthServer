package ru.cororo.authserver.protocol.packet.handler

import ru.cororo.authserver.protocol.packet.clientbound.ClientboundStatusPongPacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundStatusPingPacket
import ru.cororo.authserver.session.MinecraftSession

object StatusPingHandler : PacketHandler<ServerboundStatusPingPacket> {
    override val packetClass = ServerboundStatusPingPacket::class.java

    override fun handle(session: MinecraftSession, packet: ServerboundStatusPingPacket) {
        session.sendPacket(ClientboundStatusPongPacket(packet.payload))
    }
}