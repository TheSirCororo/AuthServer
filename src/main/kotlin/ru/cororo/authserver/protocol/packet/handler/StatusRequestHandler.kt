package ru.cororo.authserver.protocol.packet.handler

import ru.cororo.authserver.protocol.packet.clientbound.ClientboundStatusResponsePacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundStatusRequestPacket
import ru.cororo.authserver.session.MinecraftSession

object StatusRequestHandler : PacketHandler<ServerboundStatusRequestPacket> {
    override val packetClass = ServerboundStatusRequestPacket::class.java
    val motd = """
        {
            "version": {
                "name": "1.17.1",
                "protocol": 756
            },
            "players": {
                "max": 20,
                "online": 0,
                "sample": []
            },
            "description": {
                "text": "A KarbonPowered Server"
            },
            "favicon": "data:image/png;base64,<data>"
        }
    """.trimIndent()

    override fun handle(session: MinecraftSession, packet: ServerboundStatusRequestPacket) {

        session.sendPacket(ClientboundStatusResponsePacket(motd))
    }
}