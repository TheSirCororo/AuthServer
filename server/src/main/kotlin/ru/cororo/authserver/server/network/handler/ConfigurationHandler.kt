package ru.cororo.authserver.server.network.handler

import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.packet.ClientInformationPacket
import ru.cororo.authserver.protocol.packet.ClientboundKnownPacksPacket
import ru.cororo.authserver.protocol.packet.ClientboundPluginMessagePacket
import ru.cororo.authserver.protocol.packet.FeatureFlagsPacket
import ru.cororo.authserver.protocol.packet.FinishConfigurationAckPacket
import ru.cororo.authserver.protocol.packet.FinishConfigurationPacket
import ru.cororo.authserver.protocol.packet.RegistryDataPacket
import ru.cororo.authserver.protocol.packet.ServerboundKnownPacksPacket
import ru.cororo.authserver.protocol.packet.UpdateTagsPacket
import ru.cororo.authserver.server.AuthServerImpl
import ru.cororo.authserver.server.network.Connection
import ru.cororo.authserver.server.network.PacketHandler
import ru.cororo.authserver.server.player.LimboPlayer

/**
 * Configuration state (1.20.2+): brand, feature flags, known packs (1.20.5+), registries and tags exactly as the
 * vanilla server of the client's version sends them.
 */
class ConfigurationHandler(
    private val server: AuthServerImpl,
    private val connection: Connection,
    private val player: LimboPlayer,
) : PacketHandler {
    private val data = GameData.version(connection.version)
    private var registriesSent = false
    private var finishSent = false

    fun start() {
        connection.send(ClientboundPluginMessagePacket(PlayHandler.brandChannel(connection.version), PlayHandler.brand()))
        connection.send(FeatureFlagsPacket(data.features))
        if (connection.version >= ProtocolVersion.MINECRAFT_1_20_5) {
            connection.send(ClientboundKnownPacksPacket(data.knownPacks))
        } else {
            sendRegistries()
        }
    }

    override fun handle(packet: Packet) {
        when (packet) {
            is ServerboundKnownPacksPacket -> if (!registriesSent) sendRegistries()
            is ClientInformationPacket -> player.locale = PlayHandler.locale(packet.locale)
            is FinishConfigurationAckPacket -> {
                if (!finishSent) return connection.close()
                PlayHandler(server, connection, player).start()
            }
            else -> Unit
        }
    }

    /** Full registry contents are always sent, so the result does not depend on which packs the client knows. */
    private fun sendRegistries() {
        registriesSent = true
        data.registries.forEach { connection.send(RegistryDataPacket(it)) }
        data.tags?.let { connection.send(UpdateTagsPacket(it)) }
        finishSent = true
        connection.send(FinishConfigurationPacket)
    }

    override fun disconnected() = server.removePlayer(player)
}
