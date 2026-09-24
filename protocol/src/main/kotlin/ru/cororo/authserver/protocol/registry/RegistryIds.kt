package ru.cororo.authserver.protocol.registry

import ru.cororo.authserver.protocol.ProtocolVersion

/**
 * Network IDs of the few registry entries packet codecs need (menu types, item component types), generated from
 * Mojang's registry reports by `tools/vanilla/registry_ids.py`.
 */
object RegistryIds {
    private val entries: Map<Pair<String, String>, PacketIds> = load()

    const val MENU = "minecraft:menu"
    const val DATA_COMPONENT_TYPE = "minecraft:data_component_type"

    /** The ID of [entry] in [registry] for [version], or `null` if the version does not have it. */
    fun id(registry: String, entry: String, version: ProtocolVersion): Int? = entries[registry to entry]?.idFor(version)

    fun require(registry: String, entry: String, version: ProtocolVersion): Int =
        requireNotNull(id(registry, entry, version)) { "$entry is not in $registry for $version" }

    private fun load(): Map<Pair<String, String>, PacketIds> {
        val stream = checkNotNull(RegistryIds::class.java.getResourceAsStream("/ru/cororo/authserver/protocol/registry-ids.txt")) {
            "Missing registry-ids.txt"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                val (registry, entry, spec) = line.split(" ", limit = 3)
                (registry to entry) to PacketIds.parse(spec)
            }
        }
    }
}
