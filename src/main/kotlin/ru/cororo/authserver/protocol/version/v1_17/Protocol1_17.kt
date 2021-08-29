package ru.cororo.authserver.protocol.version.v1_17

import ru.cororo.authserver.AuthServer.logger
import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.packet.clientbound.*
import ru.cororo.authserver.protocol.packet.handler.LoginEncryption
import ru.cororo.authserver.protocol.packet.handler.LoginStartHandler
import ru.cororo.authserver.protocol.packet.serverbound.*

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

@ExperimentalUnsignedTypes
class Protocol1_17 : MinecraftProtocol(inbound = { packet ->
    when (packet) {
        is ServerboundHandshakePacket -> {
            context.protocol.state = ProtocolState[packet.nextState]
        }
        is ServerboundStatusRequestPacket -> {
            context.sendPacket(ClientboundStatusResponsePacket(motd))
        }
        is ServerboundStatusPingPacket -> context.sendPacket(ClientboundStatusPongPacket(packet.payload))
        is ServerboundLoginStartPacket -> {
            LoginStartHandler.handle(this.context, packet)
        }
        is ServerboundLoginEncryptionResponsePacket -> {
            LoginEncryption.handle(this.context, packet)
        }
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
            clientbound(0x00, ClientboundLoginDisconnectPacket)
            clientbound(0x01, ClientboundLoginEncryptionPacket)
            clientbound(0x02, ClientboundLoginSuccessPacket)
            clientbound(0x03, ClientboundLoginSetCompressionPacket)

            serverbound(0x00, ServerboundLoginStartPacket)
            serverbound(0x01, ServerboundLoginEncryptionResponsePacket)
        }

        game {  }
    }

    override fun toString(): String {
        return "Protocol 1.17 (state=$state, serverbound=$serverbound, clientbound=$clientbound)"
    }
}