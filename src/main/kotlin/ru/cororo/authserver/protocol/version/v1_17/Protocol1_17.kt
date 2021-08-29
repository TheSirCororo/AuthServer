package ru.cororo.authserver.protocol.version.v1_17

import ru.cororo.authserver.AuthServer.logger
import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.packet.clientbound.ClientboundStatusPongPacket
import ru.cororo.authserver.protocol.packet.clientbound.ClientboundStatusResponsePacket
import ru.cororo.authserver.protocol.packet.handler.StatusRequestHandler
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundHandshakePacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundLoginStartPacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundStatusPingPacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundStatusRequestPacket

@ExperimentalUnsignedTypes
class Protocol1_17 : MinecraftProtocol(inbound = { packet ->
    when (packet) {
        is ServerboundHandshakePacket -> {
            context.protocol.state = ProtocolState[packet.nextState]
        }
        is ServerboundStatusRequestPacket -> {
            context.sendPacket(ClientboundStatusResponsePacket(StatusRequestHandler.motd))
        }
        is ServerboundStatusPingPacket -> context.sendPacket(ClientboundStatusPongPacket(packet.payload))
    }
    logger.info("receive packet: $packet")
}) {
    init {
        handshake {
            serverbound(0x00, ServerboundHandshakePacket)
        }

        status {
            clientbound(0x00, ClientboundStatusResponsePacket)
            clientbound(0x01, ClientboundStatusPongPacket)

            serverbound(0x00, ServerboundStatusRequestPacket)
            serverbound(0x01, ServerboundStatusPingPacket)
        }

        login {
            serverbound(0x00, ServerboundLoginStartPacket)
        }

        game {  }
    }

    override fun toString(): String {
        return "Protocol 1.17 (state=$state, serverbound=$serverbound, clientbound=$clientbound)"
    }
}