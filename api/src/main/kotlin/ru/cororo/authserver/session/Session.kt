package ru.cororo.authserver.session

import ru.cororo.authserver.NameHolder
import ru.cororo.authserver.UniqueIdHolder
import ru.cororo.authserver.player.GameProfile
import ru.cororo.authserver.player.Player
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.Protocolable
import java.net.InetSocketAddress
import java.util.*

/**
 * Player session
 */
interface Session : Protocolable, UniqueIdHolder, NameHolder {
    /**
     * Player remote address
     */
    val address: InetSocketAddress

    /**
     * Player protocol version
     */
    val protocolVersion: ProtocolVersion

    /**
     * Player username
     * Null if player don't authenticated
     */
    val username: String?

    /**
     * Player game profile
     */
    val playerProfile: GameProfile?

    /**
     * If session is active (if connection is established)
     */
    val isActive: Boolean

    /**
     * Does session - joined player?
     */
    val isPlayer: Boolean

    /**
     * Get player if session is joined player
     */
    val player: Player?

    /**
     * Session (player) unique id
     */
    override val uniqueId: UUID

    /**
     * Session (player) name. Equals [username] but not nullable
     */
    override val name: String get() = username ?: ""
}