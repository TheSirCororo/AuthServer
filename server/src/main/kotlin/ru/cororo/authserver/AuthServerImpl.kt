package ru.cororo.authserver

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.util.network.*
import io.netty.channel.socket.SocketChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import ru.cororo.authserver.network.NetworkServer
import ru.cororo.authserver.player.MinecraftPlayer
import ru.cororo.authserver.protocol.ProtocolVersions
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.util.generateRSAKeyPair
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.session.Session
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

val logger get() = AuthServerImpl.logger

object AuthServerImpl : AuthServer {
    override val logger = LoggerFactory.getLogger("AuthServer")
    override lateinit var address: InetSocketAddress
        private set
    override val coroutineContext: CoroutineContext =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher() + CoroutineName("AuthServer")
    override val sessions = mutableSetOf<MinecraftSession>()
    override val players = mutableSetOf<MinecraftPlayer>()

    // Key pair for login
    val keys = generateRSAKeyPair()

    // Http client for making requests to mojang session server
    val sessionClient = HttpClient(CIO)

    // You can't send fake packets to the server without session
    override fun <T : Packet> sendPacket(packet: T) {
        throw UnsupportedOperationException()
    }

    // You can handle serverbound packets
    override fun <T : Packet> addPacketListener(listener: PacketListener<T>) {

    }

    override fun <T : Packet> sendFakePacket(packet: T, session: Session) {
        AuthServerImpl.launch {
            (session as MinecraftSession).protocol.inboundPipeline.execute(session, packet)
        }
    }

    fun onDisconnect(session: MinecraftSession, throwable: Throwable?, socket: io.netty.channel.Channel) {
        if (sessions.find { it.connection == socket } != null) {
            if (throwable != null) {
                logger.error("Client $session exists with error", throwable)
            }
            if (socket.isActive) {
                socket.close()
            }
            sessions.remove(session)
            session.isActive = false
            println("session $session disconnect")
            if (session.isPlayer) {
                players.remove(session._player!!)
                logger.info("Player ${session._player} disconnected")
            }
        }
    }

    suspend fun start(hostname: String = "127.0.0.1", port: Int = 5000) {
        withContext(Dispatchers.IO) {
            NetworkServer.bind(hostname, port)
            NetworkServer.serverChannel.closeFuture().syncUninterruptibly()
        }
    }

    fun createSession(connection: SocketChannel): MinecraftSession {
        val socketAddress = connection.remoteAddress()
        val inetAddress = InetSocketAddress(socketAddress.hostname, socketAddress.port)
        return MinecraftSession(connection, Channel(), ProtocolVersions.default, inetAddress)
    }
}
