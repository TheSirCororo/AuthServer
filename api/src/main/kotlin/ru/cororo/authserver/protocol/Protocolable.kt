package ru.cororo.authserver.protocol

import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketListener
import ru.cororo.authserver.protocol.packet.listener

/**
 * The object to whom you can send packets and from whom you can receive packets
 * Usually client or server
 */
interface Protocolable {
    fun <T : Packet> sendPacket(packet: T)

    fun <T : Packet> addPacketListener(listener: PacketListener<T>)

}

inline fun <reified T : Packet> Protocolable.addPacketListener(crossinline handle: (T, Protocolable) -> Unit) = addPacketListener(listener(handle))