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
import ru.cororo.authserver.protocol.startReadingConnection
import ru.cororo.authserver.protocol.startWritingConnection
import ru.cororo.authserver.protocol.utils.generate4CharsRandomString
import ru.cororo.authserver.protocol.utils.generateRSAKeyPair
import ru.cororo.authserver.protocol.utils.toPEMString
import ru.cororo.authserver.session.MinecraftSession
import java.math.BigInteger
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

object AuthServer : CoroutineScope {
    val logger = LoggerFactory.getLogger("AuthServer")
    lateinit var input: ByteReadChannel
        private set
    lateinit var output: ByteWriteChannel
        private set
    lateinit var address: InetSocketAddress
        private set
    override val coroutineContext: CoroutineContext =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher() + CoroutineName("AuthServer")
    private val sessions = mutableSetOf<MinecraftSession>()
    val keys = generateRSAKeyPair()
    val sessionClient = HttpClient(CIO)
    val sessionsSet = sessions.toSet()
    val serverId = BigInteger(generate4CharsRandomString().toByteArray()).toString(16)

    suspend fun start(hostname: String = "127.0.0.1", port: Int = 5000) {
        launch {
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
                            println("Disconnected from ${address.address}")
                        }
                    }
                }
            } finally {
                server.close()
                server.awaitClosed()
                this@AuthServer.cancel()
            }
        }
    }

    private fun createSession(connection: Connection): MinecraftSession {
        val socketAddress = connection.socket.remoteAddress
        val inetAddress = InetSocketAddress(socketAddress.hostname, socketAddress.port)
        val session = MinecraftSession(connection, Channel(), 756, inetAddress)
        sessions.add(session)
        logger.info("Player connection from ${inetAddress.address}")
        return session
    }
}
