package ru.cororo.authserver.session

import io.ktor.network.sockets.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.cororo.authserver.AuthServer
import ru.cororo.authserver.protocol.MinecraftProtocol
import ru.cororo.authserver.protocol.protocol
import java.net.InetSocketAddress

class MinecraftSession(
    val connection: Connection,
    val sendChannel: Channel<Any>,
    val protocolVersion: Int,
    val address: InetSocketAddress
) {
    val protocol get() = MinecraftProtocol.defaultProtocol

    fun sendPacket(packet: Any) {
        println("Sending packet $packet")
        AuthServer.launch {
            sendChannel.send(packet)
        }
    }
}