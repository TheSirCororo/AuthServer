package ru.cororo.authserver.protocol.packet

import ru.cororo.authserver.protocol.Protocolable
import ru.cororo.authserver.protocol.packet.Packet

/**
 * Packet listener. It can be made for serverbound packets and clientbound too
 */
interface PacketListener<T : Packet> {
    val packetClass: Class<T>

    /**
     * Function for handling packets
     * @param packet packet to send or to receive
     * @param protocolable IF packet is clientbound, this is session where the packet will be sent AND IF packet is serverbound, this is session from which packet was received
     */
    fun handle(packet: T, protocolable: Protocolable)
}

/**
 * Function for easy creating packet listeners
 */
inline fun <reified T : Packet> handler(crossinline handle: (T, Protocolable) -> Unit) = object : PacketListener<T> {
    override val packetClass: Class<T> = T::class.java

    override fun handle(packet: T, protocolable: Protocolable) {
        handle(packet, protocolable)
    }
}