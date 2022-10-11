package ru.cororo.authserver.protocol.version.v1_18

import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.packet.clientbound.game.*
import ru.cororo.authserver.protocol.packet.clientbound.login.*
import ru.cororo.authserver.protocol.packet.clientbound.status.*
import ru.cororo.authserver.protocol.packet.serverbound.game.*
import ru.cororo.authserver.protocol.packet.serverbound.handshake.ServerboundHandshakePacket
import ru.cororo.authserver.protocol.packet.serverbound.login.*
import ru.cororo.authserver.protocol.packet.serverbound.status.*

@OptIn(ExperimentalUnsignedTypes::class)
class Protocol1_18 : MinecraftProtocol() {
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
            clientbound(0x0E, ClientboundGameServerDifficultyPacket)
            clientbound(0x18, ClientboundGamePluginMessagePacket)
            clientbound(0x1A, ClientboundGameDisconnectPacket)
            clientbound(0x21, ClientboundGameKeepAlivePacket)
            clientbound(0x26, ClientboundGameJoinPacket)
            clientbound(0x32, ClientboundGamePlayerAbilitiesPacket)

            serverbound(0x05, ServerboundGameClientSettingsPacket)
            serverbound(0x0A, ServerboundGamePluginMessagePacket)
            serverbound(0x0F, ServerboundGameKeepAlivePacket)
        }
    }

    override fun toString(): String {
        return "Protocol 1.18 (state=$state, serverbound=$serverbound, clientbound=$clientbound)"
    }
}