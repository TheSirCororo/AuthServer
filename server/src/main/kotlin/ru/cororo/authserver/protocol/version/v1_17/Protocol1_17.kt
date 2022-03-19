package ru.cororo.authserver.protocol.version.v1_17

import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.packet.clientbound.*
import ru.cororo.authserver.protocol.packet.serverbound.*

@OptIn(ExperimentalUnsignedTypes::class)
class Protocol1_17 : MinecraftProtocol() {
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

        game {
            clientbound(0x18, ClientboundGamePluginMessagePacket)
            clientbound(0x1A, ClientboundGameDisconnectPacket)
            clientbound(0x26, ClientboundGameJoinPacket)

            serverbound(0x05, ServerboundGameClientSettingsPacket)
        }
    }

    override fun toString(): String {
        return "Protocol 1.17 (state=$state, serverbound=$serverbound, clientbound=$clientbound)"
    }
}