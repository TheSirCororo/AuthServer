package ru.cororo.authserver.protocol

/**
 * Protocol version
 * @see ProtocolVersions
 */
data class ProtocolVersion(
    /**
     * Raw protocol version (see <a href="https://wiki.vg/Protocol_version_numbers">this</a> for more information)
     */
    val raw: Int,
    val possibleVersions: List<String>
)