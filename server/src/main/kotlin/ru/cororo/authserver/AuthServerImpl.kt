package ru.cororo.authserver

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import net.kyori.adventure.text.Component
import org.slf4j.LoggerFactory
import ru.cororo.authserver.player.MinecraftPlayer
import ru.cororo.authserver.protocol.ProtocolVersions
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.packet.clientbound.game.ClientboundGameDisconnectPacket
import ru.cororo.authserver.protocol.startReadingConnection
import ru.cororo.authserver.protocol.startWritingConnection
import ru.cororo.authserver.protocol.util.generateRSAKeyPair
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

    private fun onDisconnect(session: MinecraftSession, throwable: Throwable?, socket: Socket) {
        if (sessions.find { it.connection.socket == socket } != null) {
            if (throwable != null) {
                logger.error("Client $session exists with error", throwable)
            }
            if (!socket.isClosed) {
                socket.close()
            }
            sessions.remove(session)
            session.isActive = false
            if (session.isPlayer) {
                players.remove(session._player!!)
                logger.info("Player ${session._player} disconnected")
            }
        }
    }

    suspend fun start(hostname: String = "127.0.0.1", port: Int = 5000) {
        withContext(coroutineContext) {
            try {
                address = InetSocketAddress(hostname, port)
                // Starting tcp server
                val selector = ActorSelectorManager(coroutineContext)
                val tcpSocketBuilder = aSocket(selector).tcp()
                val server = tcpSocketBuilder.bind(address)
                logger.info("AuthServer bind at $address")

                try {
                    while (true) {
                        // Wait for client connection and launch task for handling it
                        val socket = server.accept()
                        launch {
                            val connection = socket.connection()
                            val session = createSession(connection)
                            sessions.add(session)
                            startReadingConnection(session, session.protocol.inboundPipeline).apply {
                                invokeOnCompletion {
                                    onDisconnect(session, it, socket)
                                }
                            }
                            startWritingConnection(session, session.protocol.outboundPipeline).apply {
                                invokeOnCompletion {
                                    onDisconnect(session, it, socket)
                                }
                            }
                        }
                    }
                } finally {
                    withContext(Dispatchers.IO) {
                        server.close()
                        server.awaitClosed()
                    }
                    this@AuthServerImpl.cancel()
                }
            } finally {
                sessions.forEach {
                    if (it.isPlayer && it.isActive) {
                        it.sendPacket(ClientboundGameDisconnectPacket(Component.text("Server closed")))
                    }
                }
            }
        }
    }

    private fun createSession(connection: Connection): MinecraftSession {
        val socketAddress = connection.socket.remoteAddress
        val inetAddress = InetSocketAddress(socketAddress.hostname, socketAddress.port)
        return MinecraftSession(connection, Channel(), ProtocolVersions.default, inetAddress)
    }
}
