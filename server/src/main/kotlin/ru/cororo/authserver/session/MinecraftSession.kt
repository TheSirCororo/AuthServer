package ru.cororo.authserver.session

import io.netty.channel.socket.SocketChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.AuthServerImpl.logger
import ru.cororo.authserver.ServerInfo
import ru.cororo.authserver.player.GameProfile
import ru.cororo.authserver.player.Player
import ru.cororo.authserver.protocol.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketBound
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.packet.clientbound.game.ClientboundGameKeepAlivePacket
import ru.cororo.authserver.protocol.packet.clientbound.status.ClientboundStatusPongPacket
import ru.cororo.authserver.protocol.packet.clientbound.status.ClientboundStatusResponsePacket
import ru.cororo.authserver.protocol.packet.handler.LoginEncryption
import ru.cororo.authserver.protocol.packet.handler.LoginStartHandler
import ru.cororo.authserver.protocol.packet.serverbound.game.ServerboundGameKeepAlivePacket
import ru.cororo.authserver.protocol.packet.serverbound.handshake.ServerboundHandshakePacket
import ru.cororo.authserver.protocol.packet.serverbound.status.ServerboundStatusPingPacket
import ru.cororo.authserver.protocol.packet.serverbound.status.ServerboundStatusRequestPacket
import ru.cororo.authserver.util.getImageBase64
import java.net.InetSocketAddress
import java.util.*
import javax.crypto.SecretKey

data class MinecraftSession(
    val connection: SocketChannel,
    val sendChannel: Channel<Any>,
    override var protocolVersion: ProtocolVersion,
    override val address: InetSocketAddress
) : Session {
    override var username: String? = null
    override var playerProfile: GameProfile? = null
        set(value) {
            field = value
            if (value != null) {
                uniqueId = value.uniqueId
            }
        }
    var _player: Player? = null
    override var uniqueId: UUID = UUID(0, 0)
    override var isActive: Boolean = false
        set(value) {
            if (value) {
                AuthServerImpl.launch {
                    while (this@MinecraftSession.isActive) {
                        if (this@MinecraftSession.isPlayer && this@MinecraftSession.protocol.state == MinecraftProtocol.ProtocolState.GAME) {
                            delay(5000L)
                            lastKeepAliveId = System.currentTimeMillis()
                            sendPacket(ClientboundGameKeepAlivePacket(lastKeepAliveId))
                        }
                    }
                }
            }

            field = value
        }
    override var isPlayer: Boolean = false
    override val player: Player? get() = if (isPlayer && isActive) _player else null

    val protocol get() = protocol(protocolVersion)
    var secret: SecretKey? = null
    private val listeners = mutableMapOf<Class<out Packet>, MutableList<PacketListener<out Packet>>>()
    private var lastKeepAliveId: Long = 0

    init {
        addPacketListener<ServerboundHandshakePacket> { packet, session ->
            session as MinecraftSession
            if (session == this) {
                session.protocolVersion = ProtocolVersions.getByRaw(packet.protocolVersion) ?: ProtocolVersions.default
                session.protocol.state = MinecraftProtocol.ProtocolState[packet.nextState]
            }
        }

        addPacketListener<ServerboundStatusRequestPacket> { _, session ->
            session as MinecraftSession
            if (session == this) {
                session.sendPacket(
                    ClientboundStatusResponsePacket(
                        Json.encodeToString(
                            ServerInfo(
                                session.protocolVersion,
                                ServerInfo.Players(0, 20, arrayOf()),
                                Component.text("Auth Server"),
                                getImageBase64("server-icon.png")
                            )
                        )
                    )
                )
            }
        }

        addPacketListener<ServerboundStatusPingPacket> { packet, session ->
            session as MinecraftSession
            if (session == this) {
                session.sendPacket(ClientboundStatusPongPacket(packet.payload))
            }
        }

        addPacketListener<ServerboundGameKeepAlivePacket> { packet, session ->
            session as MinecraftSession
            if (session == this && lastKeepAliveId == packet.keepAliveId) {
                logger.info("Got keep alive packet from $session")
            }
        }

        addPacketListener(LoginStartHandler)

        addPacketListener(LoginEncryption)
    }

    override fun <T : Packet> sendPacket(packet: T) {
        if (packet.bound == PacketBound.SERVER) {
            logger.error("Cannot send serverbound packet to client! (packet=$packet)")
            return
        }

        logger.debug("[$this] Sending packet $packet")
        AuthServerImpl.launch {
            sendChannel.send(packet)
        }
    }

    override fun <T : Packet> addPacketListener(listener: PacketListener<T>) {
        listeners.getOrPut(listener.packetClass) { mutableListOf() }.add(listener)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Packet> handle(packet: T) {
        listeners.filter { it.key.isInstance(packet) }.values.flatten().forEach {
            (it as PacketListener<T>).handle(packet, this)
        }
    }

    override fun toString(): String {
        return "MinecraftSession(username=$username, address=${address.address})"
    }

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other === this) return true
        if (other !is MinecraftSession) return false
        return username == other.username && uniqueId == other.uniqueId && address == other.address && protocolVersion == other.protocolVersion
    }

    override fun hashCode(): Int {
        var result = connection.hashCode()
        result = 31 * result + sendChannel.hashCode()
        result = 31 * result + protocolVersion.hashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + (playerProfile?.hashCode() ?: 0)
        result = 31 * result + uniqueId.hashCode()
        result = 31 * result + isActive.hashCode()
        result = 31 * result + isPlayer.hashCode()
        result = 31 * result + (secret?.hashCode() ?: 0)
        result = 31 * result + listeners.hashCode()
        return result
    }
}