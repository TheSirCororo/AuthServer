package ru.cororo.authserver.protocol.packet

/**
 * Packet that can be sent to server or client
 */
interface Packet {
    val bound: PacketBound
}