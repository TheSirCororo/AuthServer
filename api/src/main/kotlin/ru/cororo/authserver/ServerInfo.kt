package ru.cororo.authserver

import net.kyori.adventure.text.Component
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.serializer.ComponentSerializer
import kotlinx.serialization.Serializable
import ru.cororo.authserver.serializer.ProtocolVersionSerializer

/**
 * Server info (MOTD)
 * Serializable with kotlinx.serialization
 */
@Serializable
data class ServerInfo(
    @Serializable(with = ProtocolVersionSerializer::class)
    val version: ProtocolVersion,
    val players: Players,
    @Serializable(with = ComponentSerializer::class)
    val description: Component,
    val favicon: String
) {
    @Serializable
    data class Players(
        val online: Int,
        val max: Int,
        val sample: Array<String>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Players

            if (max != other.max) return false
            if (online != other.online) return false
            if (!sample.contentEquals(other.sample)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = max
            result = 31 * result + online
            result = 31 * result + sample.contentHashCode()
            return result
        }
    }
}