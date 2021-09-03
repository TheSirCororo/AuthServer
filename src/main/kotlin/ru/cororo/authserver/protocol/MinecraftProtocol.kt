package ru.cororo.authserver.protocol

import io.ktor.util.pipeline.*
import ru.cororo.authserver.protocol.packet.MinecraftPacketCodec
import ru.cororo.authserver.protocol.version.v1_17.Protocol1_17
import ru.cororo.authserver.session.MinecraftSession

@Suppress("UNCHECKED_CAST")
abstract class MinecraftProtocol(inbound: PipelineInterceptor<Any, MinecraftSession> = {}, outBound: PipelineInterceptor<Any, MinecraftSession> = {}) {
    val clientbound = mutableMapOf<Int, MinecraftPacketCodec<Any>>()
    val serverbound = mutableMapOf<Int, MinecraftPacketCodec<Any>>()
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

    fun <T : Any> getCodec(packetId: Int, bound: Bound = Bound.CLIENT): MinecraftPacketCodec<T> {
        val codec = when (bound) {
            Bound.SERVER ->
                serverbound[packetId]
                    ?: throw IllegalArgumentException("Serverbound packet with id $packetId does not exists")
            Bound.CLIENT ->
                clientbound[packetId]
                    ?: throw IllegalArgumentException("Serverbound packet with id $packetId does not exists")
        }

        return codec as MinecraftPacketCodec<T>
    }

    fun <T : Any> getCodec(packet: T, bound: Bound = Bound.CLIENT): MinecraftPacketCodec<T> =
        when (bound) {
            Bound.SERVER -> {
                serverbound.values.find { packet.javaClass == it.packetClass } as? MinecraftPacketCodec<T>
                    ?: throw IllegalArgumentException("$packet codec does not exists")
            }
            Bound.CLIENT -> {
                clientbound.values.find { packet.javaClass == it.packetClass } as? MinecraftPacketCodec<T>
                    ?: throw IllegalArgumentException("$packet codec does not exists")
            }
        }

    fun <T : Any> getPacketId(codec: MinecraftPacketCodec<T>, bound: Bound): Int =
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
        private val registeredProtocols = mutableMapOf<Int, MinecraftProtocol>()
        val defaultProtocol: MinecraftProtocol

        init {
            defaultProtocol = Protocol1_17()
            registeredProtocols[756] = defaultProtocol
        }

        fun getByVersion(version: Int): MinecraftProtocol = registeredProtocols[version] ?: defaultProtocol
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
        fun <T : Any> clientbound(packetId: Int, codec: MinecraftPacketCodec<T>) {
            clientbound[packetId] = codec
        }

        fun <T : Any> serverbound(packetId: Int, codec: MinecraftPacketCodec<T>) {
            serverbound[packetId] = codec
        }
    }
}

fun protocol(version: Int = 0) =
    if (version != 0) MinecraftProtocol.getByVersion(version) else MinecraftProtocol.defaultProtocol