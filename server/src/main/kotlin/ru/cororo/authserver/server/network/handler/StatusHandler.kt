package ru.cororo.authserver.server.network.handler

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.buffer.Components
import ru.cororo.authserver.protocol.packet.StatusPingPacket
import ru.cororo.authserver.protocol.packet.StatusPongPacket
import ru.cororo.authserver.protocol.packet.StatusRequestPacket
import ru.cororo.authserver.protocol.packet.StatusResponsePacket
import ru.cororo.authserver.server.AuthServerImpl
import ru.cororo.authserver.server.network.Connection
import ru.cororo.authserver.server.network.PacketHandler

/** Server list ping. Clients of unsupported versions see the server as incompatible but still get the MOTD. */
class StatusHandler(private val server: AuthServerImpl, private val connection: Connection, private val clientProtocol: Int) : PacketHandler {
    private var answered = false

    override fun handle(packet: Packet) {
        when (packet) {
            is StatusRequestPacket -> {
                if (answered) return connection.close()
                answered = true
                connection.send(StatusResponsePacket(status()))
            }
            is StatusPingPacket -> {
                connection.send(StatusPongPacket(packet.payload))
                connection.close()
            }
            else -> connection.close()
        }
    }

    private fun status(): String {
        val supported = ProtocolVersion.byProtocol(clientProtocol) != null
        val json = JsonObject()
        json.add("version", JsonObject().apply {
            addProperty("name", "AuthServer ${HandshakeHandler.SUPPORTED_VERSIONS}")
            addProperty("protocol", if (supported) clientProtocol else ProtocolVersion.LATEST.protocol)
        })
        json.add("players", JsonObject().apply {
            addProperty("max", server.config.status.maxPlayers)
            addProperty("online", server.players.size)
            add("sample", JsonArray())
        })
        json.add("description", Components.serializer(connection.version).serializeToTree(server.motd))
        server.favicon?.let { json.addProperty("favicon", it) }
        json.addProperty("enforcesSecureChat", false)
        return json.toString()
    }
}
