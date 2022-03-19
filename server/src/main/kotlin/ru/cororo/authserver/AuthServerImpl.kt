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
import ru.cororo.authserver.protocol.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.utils.generateRSAKeyPair
import ru.cororo.authserver.session.MinecraftSession
import ru.cororo.authserver.session.Session
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

val logger get() = AuthServerImpl.logger

object AuthServerImpl : AuthServer {
    override val logger = LoggerFactory.getLogger("AuthServer")
    lateinit var input: ByteReadChannel
        private set
    lateinit var output: ByteWriteChannel
        private set
    override lateinit var address: InetSocketAddress
        private set
    override val coroutineContext: CoroutineContext =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher() + CoroutineName("AuthServer")

    override fun <T : Packet> sendPacket(packet: T) {
        TODO("Not yet implemented")
    }

    override fun <T : Packet> addPacketListener(listener: PacketListener<T>) {
        TODO("Not yet implemented")
    }

    override val sessions = mutableSetOf<Session>()
    val keys = generateRSAKeyPair()
    val sessionClient = HttpClient(CIO)

    suspend fun start(hostname: String = "127.0.0.1", port: Int = 5000) {
        withContext(coroutineContext) {
            address = InetSocketAddress(hostname, port)
            val selector = ActorSelectorManager(coroutineContext)
            val tcpSocketBuilder = aSocket(selector).tcp()
            val server = tcpSocketBuilder.bind(address)
            logger.info("AuthServer bind at ${address.address}")

            try {
                while (true) {
                    val socket = server.accept()
                    launch {
                        try {
                            val connection = socket.connection()
                            val session = createSession(connection)
                            startReadingConnection(session, session.protocol.inboundPipeline).apply {
                                invokeOnCompletion {
                                    socket.close()
                                }
                            }
                            startWritingConnection(session, session.protocol.outboundPipeline).apply {
                                invokeOnCompletion {
                                    socket.close()
                                }
                            }
                        } catch (closed: ClosedSendChannelException) {
                            coroutineContext.cancel()
                        } catch (closed: ClosedReceiveChannelException) {
                            coroutineContext.cancel()
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
