package ru.cororo.authserver.session

import io.ktor.network.sockets.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.AuthServerImpl.logger
import ru.cororo.authserver.ServerInfo
import ru.cororo.authserver.protocol.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.packet.clientbound.ClientboundStatusPongPacket
import ru.cororo.authserver.protocol.packet.clientbound.ClientboundStatusResponsePacket
import ru.cororo.authserver.protocol.packet.handler.LoginEncryption
import ru.cororo.authserver.protocol.packet.handler.LoginStartHandler
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundHandshakePacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundStatusPingPacket
import ru.cororo.authserver.protocol.packet.serverbound.ServerboundStatusRequestPacket
import java.net.InetSocketAddress
import javax.crypto.SecretKey

data class MinecraftSession(
    val connection: Connection,
    val sendChannel: Channel<Any>,
    override var protocolVersion: ProtocolVersion,
    override val address: InetSocketAddress
) : Session {
    override var username: String? = null
    val protocol get() = protocol(protocolVersion)
    private val listeners = mutableMapOf<Class<out Packet>, MutableList<PacketListener<out Packet>>>()
    var secret: SecretKey? = null

    init {
        addPacketListener<ServerboundHandshakePacket> { packet, session ->
            session as MinecraftSession
            session.protocolVersion = ProtocolVersions.getByRaw(packet.protocolVersion) ?: ProtocolVersions.default
            session.protocol.state = MinecraftProtocol.ProtocolState[packet.nextState]
        }

        addPacketListener<ServerboundStatusRequestPacket> { _, session ->
            session as MinecraftSession
            session.sendPacket(
                ClientboundStatusResponsePacket(
                    Json.encodeToString(
                        ServerInfo(
                            session.protocolVersion,
                            ServerInfo.Players(0, 20, arrayOf()),
                            Component.text("Auth Server"),
                            "data:image/png;base64,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"
                        )
                    )
                )
            )
        }

        addPacketListener<ServerboundStatusPingPacket> { packet, session ->
            session as MinecraftSession
            session.sendPacket(ClientboundStatusPongPacket(packet.payload))
        }

        addPacketListener(LoginStartHandler)

        addPacketListener(LoginEncryption)
    }

    override fun <T : Packet> sendPacket(packet: T) {
        logger.debug("[$this] Sending packet $packet")
        AuthServerImpl.launch {
            sendChannel.send(packet)
        }
    }

    override fun <T : Packet> addPacketListener(listener: PacketListener<T>) {
        listeners.putIfAbsent(listener.packetClass, mutableListOf())
        listeners[listener.packetClass]!!.add(listener)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Packet> handle(packet: T) {
        listeners[packet.javaClass]?.forEach {
            (it as PacketListener<T>).handle(packet, this)
        }
    }

    override fun toString(): String {
        return "MinecraftSession(username=$username, address=${address.address}, protocolVersion=$protocolVersion)"
    }
}