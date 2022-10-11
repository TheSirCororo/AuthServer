package ru.cororo.authserver

import kotlinx.coroutines.CoroutineScope
import org.slf4j.Logger
import ru.cororo.authserver.player.Player
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
     * Sessions set. Contains ping status sessions too
     */
    val sessions: Set<Session>

    /**
     * Players set. Contains only players who passed LOGIN stage
     */
    val players: Set<Player>

    /**
     * You can't send fake packets to the server without session
     * This function in AuthServer always throws [UnsupportedOperationException]
     * @throws UnsupportedOperationException
     * @see sendFakePacket
     */
    override fun <T : Packet> sendPacket(packet: T)

    /**
     * You can handle serverbound or clientbound packets of any players
     */
    override fun <T : Packet> addPacketListener(listener: PacketListener<T>)

    /**
     * This function is used to send fake packets
     */
    fun <T : Packet> sendFakePacket(packet: T, session: Session)
}