package ru.cororo.authserver.session

import io.ktor.network.sockets.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.cororo.authserver.AuthServerImpl
import ru.cororo.authserver.AuthServerImpl.logger
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.protocol
import java.net.InetSocketAddress

data class MinecraftSession(
    val connection: Connection,
    val sendChannel: Channel<Any>,
    override val protocolVersion: ProtocolVersion,
    override val address: InetSocketAddress
) : Session {
    lateinit var username: String
    val protocol get() = protocol(protocolVersion)

    override fun <T : Packet> sendPacket(packet: T) {
        logger.debug("[$this] Sending packet $packet")
        AuthServerImpl.launch {
            sendChannel.send(packet)
        }
    }

    override fun <T : Packet> addPacketListener(listener: PacketListener<T>) {

    }

    override fun toString(): String {
        return "MinecraftSession(${address.address}, $protocolVersion)"
    }
}