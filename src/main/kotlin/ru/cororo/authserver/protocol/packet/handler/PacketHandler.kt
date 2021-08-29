package ru.cororo.authserver.protocol.packet.handler

import ru.cororo.authserver.session.MinecraftSession

interface PacketHandler<out T> {
    val packetClass: Class<out T>

    suspend fun handle(session: MinecraftSession, packet: @UnsafeVariance T)
}