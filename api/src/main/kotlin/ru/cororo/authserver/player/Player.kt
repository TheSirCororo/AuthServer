package ru.cororo.authserver.player

import ru.cororo.authserver.NameHolder
import ru.cororo.authserver.UniqueIdHolder
import ru.cororo.authserver.session.Session
import ru.cororo.authserver.world.Position
import ru.cororo.authserver.world.Locatable
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.*

/**
 * Player class
 */
interface Player : UniqueIdHolder, NameHolder, Locatable {
    /**
     * Player username (up to 16 symbols)
     */
    val username: String

    /**
     * Player game profile (contains player data, e.g. skin texture)
     */
    val gameProfile: GameProfile

    /**
     * Player session (null when player is offline).
     * Can be used for sending packets and add packet listener
     */
    val session: Session?

    /**
     * Player remote address
     */
    val remoteAddress: InetSocketAddress

    /**
     * Player unique id
     */
    override val uniqueId: UUID

    /**
     * Player username
     * Equals [username] field
     */
    override val name: String get() = username

    /**
     * Player location
     */
    override val position: Position
}