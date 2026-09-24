package ru.cororo.authserver.protocol.registry

import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.PacketCodec
import ru.cororo.authserver.protocol.PacketDirection
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import java.util.EnumMap

/**
 * Maps packet models to IDs and codecs for every (version, state, direction).
 * Tables are computed eagerly, so a malformed registration fails at startup rather than on the wire.
 */
class PacketRegistry private constructor(registrations: List<Registration<*>>) {
    private val tables: Map<ProtocolVersion, Map<ProtocolState, Map<PacketDirection, PacketTable>>> =
        EnumMap<ProtocolVersion, Map<ProtocolState, Map<PacketDirection, PacketTable>>>(ProtocolVersion::class.java).apply {
            for (version in ProtocolVersion.entries) {
                put(version, ProtocolState.entries.associateWith { state ->
                    PacketDirection.entries.associateWith { direction ->
                        PacketTable.build(version, state, direction,
                            registrations.filter { it.state == state && it.direction == direction })
                    }
                })
            }
        }

    fun table(version: ProtocolVersion, state: ProtocolState, direction: PacketDirection): PacketTable =
        tables.getValue(version).getValue(state).getValue(direction)

    internal class Registration<T : Packet>(
        val state: ProtocolState,
        val direction: PacketDirection,
        val type: Class<T>,
        val codec: PacketCodec<T>,
        val ids: PacketIds,
        /** Several models may share one ID (legacy multiplexed packets); only the primary one decodes it. */
        val decodes: Boolean,
    )

    class Builder internal constructor() {
        internal val registrations = mutableListOf<Registration<*>>()

        fun state(state: ProtocolState, block: StateBuilder.() -> Unit) = StateBuilder(state).block()

        inner class StateBuilder internal constructor(private val state: ProtocolState) {
            fun generated(direction: PacketDirection, name: String) = PacketIds.generated(state, direction, name)

            inline fun <reified T : Packet> clientbound(codec: PacketCodec<T>, ids: PacketIds, decodes: Boolean = true) =
                register(PacketDirection.CLIENTBOUND, T::class.java, codec, ids, decodes)

            inline fun <reified T : Packet> clientbound(codec: PacketCodec<T>, ids: String) =
                clientbound(codec, PacketIds.parse(ids))

            inline fun <reified T : Packet> clientbound(name: String, codec: PacketCodec<T>) =
                clientbound(codec, generated(PacketDirection.CLIENTBOUND, name))

            inline fun <reified T : Packet> serverbound(codec: PacketCodec<T>, ids: PacketIds) =
                register(PacketDirection.SERVERBOUND, T::class.java, codec, ids, true)

            inline fun <reified T : Packet> serverbound(codec: PacketCodec<T>, ids: String) =
                serverbound(codec, PacketIds.parse(ids))

            inline fun <reified T : Packet> serverbound(name: String, codec: PacketCodec<T>) =
                serverbound(codec, generated(PacketDirection.SERVERBOUND, name))

            @PublishedApi
            internal fun <T : Packet> register(
                direction: PacketDirection, type: Class<T>, codec: PacketCodec<T>, ids: PacketIds, decodes: Boolean,
            ) {
                registrations += Registration(state, direction, type, codec, ids, decodes)
            }
        }
    }

    companion object {
        fun build(block: Builder.() -> Unit): PacketRegistry = PacketRegistry(Builder().apply(block).registrations)
    }
}

/** Packets of one version, state and direction. */
class PacketTable private constructor(
    private val codecsById: Map<Int, PacketCodec<out Packet>>,
    private val idsByType: Map<Class<out Packet>, Int>,
    private val codecsByType: Map<Class<out Packet>, PacketCodec<out Packet>>,
) {
    fun codec(id: Int): PacketCodec<out Packet>? = codecsById[id]

    fun id(type: Class<out Packet>): Int? = idsByType[type]

    /** Returns the ID and codec for [packet], or `null` if the packet does not exist in this version. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Packet> lookup(packet: T): Pair<Int, PacketCodec<T>>? {
        val id = idsByType[packet.javaClass] ?: return null
        return id to codecsByType.getValue(packet.javaClass) as PacketCodec<T>
    }

    fun supports(type: Class<out Packet>): Boolean = type in idsByType

    internal companion object {
        fun build(
            version: ProtocolVersion, state: ProtocolState, direction: PacketDirection,
            registrations: List<PacketRegistry.Registration<*>>,
        ): PacketTable {
            val byId = HashMap<Int, PacketCodec<out Packet>>()
            val ids = HashMap<Class<out Packet>, Int>()
            val codecs = HashMap<Class<out Packet>, PacketCodec<out Packet>>()
            for (registration in registrations) {
                val id = registration.ids.idFor(version) ?: continue
                check(ids.put(registration.type, id) == null) {
                    "${registration.type.simpleName} is registered twice for $version ${state.key} ${direction.key}"
                }
                codecs[registration.type] = registration.codec
                if (registration.decodes) {
                    val previous = byId.put(id, registration.codec)
                    check(previous == null) {
                        "Packet ID 0x%02x is used twice for $version ${state.key} ${direction.key}".format(id)
                    }
                }
            }
            return PacketTable(byId, ids, codecs)
        }
    }
}
