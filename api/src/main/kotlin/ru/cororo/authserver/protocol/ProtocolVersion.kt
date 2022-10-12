package ru.cororo.authserver.protocol

import kotlinx.serialization.Serializable
import ru.cororo.authserver.serializer.ProtocolVersionSerializer

/**
 * Protocol version
 * @see ProtocolVersions
 */
@Serializable(with = ProtocolVersionSerializer::class)
data class ProtocolVersion(
    /**
     * Raw protocol version (see <a href="https://wiki.vg/Protocol_version_numbers">this</a> for more information)
     */
    val raw: Int,
    val possibleVersions: List<String>
)