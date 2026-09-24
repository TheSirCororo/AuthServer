package ru.cororo.authserver.server.network.handler

import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.packet.HandshakePacket
import ru.cororo.authserver.server.AuthServerImpl
import ru.cororo.authserver.server.config.ForwardingMode
import ru.cororo.authserver.server.network.Connection
import ru.cororo.authserver.server.network.LegacyForwarding
import ru.cororo.authserver.server.network.PacketHandler
import ru.cororo.authserver.server.network.forwardedAddress
import java.util.Locale

class HandshakeHandler(private val server: AuthServerImpl, private val connection: Connection) : PacketHandler {
    override fun handle(packet: Packet) {
        if (packet !is HandshakePacket) return connection.close()
        val version = ProtocolVersion.byProtocol(packet.protocolVersion)
        // Unknown versions still get a status answer or a readable kick, encoded like the closest supported version.
        connection.version = version ?: if (packet.protocolVersion < ProtocolVersion.OLDEST.protocol) ProtocolVersion.OLDEST else ProtocolVersion.LATEST
        when (packet.intent) {
            HandshakePacket.Intent.STATUS -> connection.switchState(ProtocolState.STATUS, StatusHandler(server, connection, packet.protocolVersion))
            HandshakePacket.Intent.LOGIN, HandshakePacket.Intent.TRANSFER -> login(packet, version)
        }
    }

    private fun login(packet: HandshakePacket, version: ProtocolVersion?) {
        var forwarded: LegacyForwarding.Result.Accepted? = null
        if (server.config.proxy.forwarding == ForwardingMode.LEGACY) {
            // An empty secret only passes validation when unprotected legacy forwarding was explicitly allowed.
            when (val result = LegacyForwarding.read(packet.serverAddress, server.config.proxy.secret)) {
                is LegacyForwarding.Result.Accepted -> forwarded = result
                else -> {
                    connection.switchState(ProtocolState.LOGIN, LoginHandler(server, connection, null))
                    return connection.disconnect(server.messages.render(Locale.ENGLISH, "kick-proxy-only"))
                }
            }
            connection.address = forwardedAddress(forwarded.player.address, connection.address.port)
        }
        connection.switchState(ProtocolState.LOGIN, LoginHandler(server, connection, forwarded?.player))
        if (version == null) {
            connection.disconnect(server.messages.render(Locale.ENGLISH, "kick-unsupported-version", "versions" to SUPPORTED_VERSIONS))
        }
    }

    companion object {
        val SUPPORTED_VERSIONS = "${ProtocolVersion.OLDEST.releases.first()}-${ProtocolVersion.LATEST.releases.last()}"
    }
}
