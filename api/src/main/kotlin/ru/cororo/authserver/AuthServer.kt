package ru.cororo.authserver

import kotlinx.coroutines.CoroutineScope
import org.slf4j.Logger
import ru.cororo.authserver.protocol.Protocolable
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.session.Session
import java.net.InetSocketAddress

/**
 * Auth server instance
 */
interface AuthServer : CoroutineScope, Protocolable {
    /**
     * Logger that used by server
     */
    val logger: Logger

    /**
     * Auth server local address
     */
    val address: InetSocketAddress

    /**
     * Player sessions set
     */
    val sessions: Set<Session>

    /**
     * You can't send fake packets to the server without session
     * This function in AuthServer always throws UnsupportedOperationException
     * @throws IllegalStateException
     * @see sendFakePacket
     */
    override fun <T : Packet> sendPacket(packet: T)

    /**
     * It's used to create serverbound packet listener
     */
    override fun <T : Packet> addPacketListener(listener: PacketListener<T>)

    /**
     * This function is used to send fake packets
     */
    fun <T : Packet> sendFakePacket(packet: T, session: Session)
}