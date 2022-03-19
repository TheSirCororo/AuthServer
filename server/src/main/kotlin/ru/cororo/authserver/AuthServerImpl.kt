package ru.cororo.authserver

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory
import ru.cororo.authserver.protocol.ProtocolVersions
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.startReadingConnection
import ru.cororo.authserver.protocol.startWritingConnection
import ru.cororo.authserver.protocol.utils.generateRSAKeyPair
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

    suspend fun start(hostname: String = "127.0.0.1", port: Int = 5000) {
        withContext(coroutineContext) {
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
                                if (it != null) {
                                    logger.error("Client $session exists with error", it)
                                }
                                socket.close()
                                sessions.remove(session)
                            }
                        }
                        startWritingConnection(session, session.protocol.outboundPipeline).apply {
                            invokeOnCompletion {
                                if (it != null) {
                                    logger.error("Client $session exists with error", it)
                                }
                                socket.close()
                                sessions.remove(session)
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
        }
    }

    private fun createSession(connection: Connection): MinecraftSession {
        val socketAddress = connection.socket.remoteAddress
        val inetAddress = InetSocketAddress(socketAddress.hostname, socketAddress.port)
        return MinecraftSession(connection, Channel(), ProtocolVersions.default, inetAddress)
    }
}
