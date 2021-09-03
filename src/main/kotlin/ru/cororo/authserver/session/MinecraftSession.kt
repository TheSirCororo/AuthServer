package ru.cororo.authserver.session

import io.ktor.network.sockets.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.cororo.authserver.AuthServer
import ru.cororo.authserver.AuthServer.logger
import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.protocol
import java.net.InetSocketAddress

data class MinecraftSession(
    val connection: Connection,
    val sendChannel: Channel<Any>,
    val protocolVersion: Int,
    val address: InetSocketAddress,
) {
    lateinit var username: String
    val protocol get() = protocol(protocolVersion)

    fun sendPacket(packet: Any) {
        logger.info("[$this] Sending packet $packet")
        AuthServer.launch {
            sendChannel.send(packet)
        }
    }

    override fun toString(): String {
        return "MinecraftSession(${address.address}, $protocolVersion)"
    }
}