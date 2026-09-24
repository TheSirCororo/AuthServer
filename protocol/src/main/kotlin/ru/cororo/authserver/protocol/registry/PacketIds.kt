package ru.cororo.authserver.protocol.registry

import ru.cororo.authserver.protocol.PacketDirection
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion

/**
 * Packet IDs of one packet across versions, stored as change points: an ID applies from its version until the next
 * point. `null` means the packet does not exist in that range.
 */
class PacketIds private constructor(private val points: List<Pair<ProtocolVersion, Int?>>) {
    fun idFor(version: ProtocolVersion): Int? = points.lastOrNull { it.first <= version }?.second

    /** IDs from this spec before [version], from [other] afterwards. */
    fun until(version: ProtocolVersion, other: PacketIds): PacketIds =
        PacketIds(points.filter { it.first < version } + (version to other.idFor(version)) +
            other.points.filter { it.first > version })

    /** Restricts the spec to `[from, until)`. */
    fun between(from: ProtocolVersion, until: ProtocolVersion? = null): PacketIds {
        val kept = points.filter { it.first > from && (until == null || it.first < until) }
        return PacketIds(listOf(ProtocolVersion.OLDEST to null, from to idFor(from)) + kept +
            listOfNotNull(until?.let { it to null }))
    }

    override fun toString(): String = points.joinToString(" ") { (version, id) ->
        "${version.protocol}=${id?.let { "0x%02x".format(it) } ?: "-"}"
    }

    companion object {
        /** Parses `47=0x01 107=0x23 755=-` style specs. Protocol numbers must be supported versions. */
        fun parse(spec: String): PacketIds = PacketIds(spec.trim().split(Regex("\\s+")).map { point ->
            val (protocol, id) = point.split('=')
            val version = requireNotNull(ProtocolVersion.byProtocol(protocol.toInt())) { "Unknown protocol $protocol" }
            version to if (id == "-") null else Integer.decode(id)
        }.sortedBy { it.first })

        /** IDs generated from vanilla sources, see `tools/vanilla/packet_table.py`. */
        fun generated(state: ProtocolState, direction: PacketDirection, name: String): PacketIds =
            requireNotNull(GeneratedTable.entries[Triple(state, direction, name)]) {
                "No generated packet IDs for ${state.key} ${direction.key} $name"
            }
    }

    private object GeneratedTable {
        val entries: Map<Triple<ProtocolState, PacketDirection, String>, PacketIds> = load()

        private fun load(): Map<Triple<ProtocolState, PacketDirection, String>, PacketIds> {
            val stream = checkNotNull(PacketIds::class.java.getResourceAsStream("/ru/cororo/authserver/protocol/packet-ids.txt")) {
                "Missing packet-ids.txt"
            }
            return stream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                    val (state, direction, name, spec) = line.split(" ", limit = 4)
                    Triple(ProtocolState.valueOf(state.uppercase()), PacketDirection.valueOf(direction.uppercase()), name) to
                        parse(spec)
                }
            }
        }
    }
}
