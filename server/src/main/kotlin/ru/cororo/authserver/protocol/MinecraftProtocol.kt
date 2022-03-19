package ru.cororo.authserver.protocol

import io.ktor.util.pipeline.*
import ru.cororo.authserver.protocol.packet.Packet
import ru.cororo.authserver.protocol.packet.PacketCodec
import ru.cororo.authserver.protocol.version.v1_17.Protocol1_17
import ru.cororo.authserver.session.MinecraftSession

@Suppress("UNCHECKED_CAST")
abstract class MinecraftProtocol(inbound: PipelineInterceptor<Any, MinecraftSession> = {}, outBound: PipelineInterceptor<Any, MinecraftSession> = {}) {
    val clientbound = mutableMapOf<Int, PacketCodec<out Packet>>()
    val serverbound = mutableMapOf<Int, PacketCodec<out Packet>>()
    val inboundPipeline = Pipeline(PipelinePhase("packets"), listOf(inbound))
    val outboundPipeline = Pipeline(PipelinePhase("packets"), listOf(outBound))

    private lateinit var handshakeBuilder: (ProtocolBuilder) -> Unit
    private lateinit var statusBuilder: (ProtocolBuilder) -> Unit
    private lateinit var loginBuilder: (ProtocolBuilder) -> Unit
    private lateinit var gameBuilder: (ProtocolBuilder) -> Unit

    var state: ProtocolState = ProtocolState.HANDSHAKE
        set(value) {
            field = value
            when (value) {
                ProtocolState.HANDSHAKE -> handshakeBuilder(ProtocolBuilder())
                ProtocolState.STATUS -> statusBuilder(ProtocolBuilder())
                ProtocolState.LOGIN -> loginBuilder(ProtocolBuilder())
                ProtocolState.GAME -> gameBuilder(ProtocolBuilder())
            }
        }

    fun handshake(block: ProtocolBuilder.() -> Unit) {
        handshakeBuilder = block
    }

    fun status(block: ProtocolBuilder.() -> Unit) {
        statusBuilder = block
    }

    fun login(block: ProtocolBuilder.() -> Unit) {
        loginBuilder = block
    }

    fun game(block: ProtocolBuilder.() -> Unit) {
        gameBuilder = block
    }

    fun <T : Packet> getCodec(packetId: Int, bound: Bound = Bound.CLIENT): PacketCodec<T> {
        val codec = when (bound) {
            Bound.SERVER ->
                serverbound[packetId]
                    ?: throw IllegalArgumentException("Serverbound packet with id $packetId does not exists")
            Bound.CLIENT ->
                clientbound[packetId]
                    ?: throw IllegalArgumentException("Serverbound packet with id $packetId does not exists")
        }

        return codec as PacketCodec<T>
    }

    fun <T : Packet> getCodec(packet: T, bound: Bound = Bound.CLIENT): PacketCodec<T> =
        when (bound) {
            Bound.SERVER -> {
                serverbound.values.find { packet.javaClass == it.packetClass } as? PacketCodec<T>
                    ?: throw IllegalArgumentException("$packet codec does not exists")
            }
            Bound.CLIENT -> {
                clientbound.values.find { packet.javaClass == it.packetClass } as? PacketCodec<T>
                    ?: throw IllegalArgumentException("$packet codec does not exists")
            }
        }

    fun <T : Packet> getPacketId(codec: PacketCodec<T>, bound: Bound): Int =
        when (bound) {
            Bound.SERVER -> {
                serverbound.filter { it.value == codec }.keys.first()
            }
            Bound.CLIENT -> {
                clientbound.filter { it.value == codec }.keys.first()
            }
        }


    enum class Bound {
        SERVER,
        CLIENT
    }

    companion object {
        private val registeredProtocols = mutableMapOf<ProtocolVersion, MinecraftProtocol>()
        val defaultProtocol: MinecraftProtocol

        init {
            registeredProtocols[ProtocolVersions.v1_17] = Protocol1_17()
            defaultProtocol = registeredProtocols.values.last()
        }

        fun getByVersion(version: ProtocolVersion): MinecraftProtocol = registeredProtocols[version] ?: defaultProtocol
    }

    enum class ProtocolState {
        HANDSHAKE,
        STATUS,
        LOGIN,
        GAME;

        companion object {
            private val values = values()

            operator fun get(state: Int): ProtocolState = values[state]
        }
    }

    inner class ProtocolBuilder internal constructor() {
        fun <T : Packet> clientbound(packetId: Int, codec: PacketCodec<T>) {
            clientbound[packetId] = codec
        }

        fun <T : Packet> serverbound(packetId: Int, codec: PacketCodec<T>) {
            serverbound[packetId] = codec
        }
    }
}

fun protocol(version: ProtocolVersion = ProtocolVersions.default) = MinecraftProtocol.getByVersion(version)