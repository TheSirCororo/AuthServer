package ru.cororo.authserver.api.event

import net.kyori.adventure.text.Component
import ru.cororo.authserver.api.inventory.Item
import ru.cororo.authserver.api.player.Player
import ru.cororo.authserver.api.player.Position
import ru.cororo.authserver.bridge.AuthMethod
import ru.cororo.authserver.bridge.AuthMode
import ru.cororo.authserver.bridge.FailureReason
import ru.cororo.authserver.protocol.ProtocolVersion
import java.net.InetSocketAddress

// ------------------------------------------------------------------------------------------------ server

/** Plugins are enabled and the server accepts connections. */
class ServerStartedEvent : Event

/** The server is shutting down; players are still connected. */
class ServerStoppingEvent : Event

// ------------------------------------------------------------------------------------------------ connection

/**
 * A client started logging in. Handlers may deny it or choose how it authenticates.
 * Behind a proxy the mode is fixed by the proxy and [mode] changes are ignored.
 *
 * @property mode [AuthMode.ONLINE] to require Mojang authentication, [AuthMode.OFFLINE] for a password login
 */
class PreLoginEvent(
    val username: String,
    val address: InetSocketAddress,
    val protocolVersion: ProtocolVersion,
    var mode: AuthMode,
) : Event {
    var denyReason: Component? = null
        private set

    val isDenied: Boolean get() = denyReason != null

    fun deny(reason: Component) {
        denyReason = reason
    }

    fun allow() {
        denyReason = null
    }
}

// ------------------------------------------------------------------------------------------------ player

/** Base class of events about one player. */
abstract class PlayerEvent(val player: Player) : Event

/** The player entered the limbo and is about to be asked to log in (unless already authenticated). */
class PlayerJoinEvent(player: Player) : PlayerEvent(player)

class PlayerQuitEvent(player: Player) : PlayerEvent(player)

/** A registration is about to be stored. Cancel with a [reason] shown to the player. */
class PlayerRegisterEvent(player: Player) : PlayerEvent(player), Cancellable {
    override var isCancelled: Boolean = false
    var reason: Component? = null
}

/** A password was checked. [remainingAttempts] is how many tries are left before the player is kicked. */
class PlayerLoginAttemptEvent(player: Player, val success: Boolean, val remainingAttempts: Int) : PlayerEvent(player)

/**
 * The player is authenticated. Behind a proxy the result is reported to the proxy plugin together with
 * [targetServer]; set it to route this player to a specific server instead of the proxy's routing rules.
 */
class PlayerAuthenticatedEvent(
    player: Player,
    val mode: AuthMode,
    val method: AuthMethod,
    var targetServer: String? = null,
) : PlayerEvent(player)

/** Authentication failed and the player is about to be disconnected. */
class PlayerAuthFailedEvent(player: Player, val reason: FailureReason) : PlayerEvent(player)

/** Chat message (not a command). Unauthenticated players' chat is always suppressed after this event. */
class PlayerChatEvent(player: Player, var message: String) : PlayerEvent(player), Cancellable {
    override var isCancelled: Boolean = false
}

/** A command, without the leading slash, before it is dispatched. */
class PlayerCommandEvent(player: Player, var command: String) : PlayerEvent(player), Cancellable {
    override var isCancelled: Boolean = false
}

/** The client moved. Cancelling teleports it back to [from]. */
class PlayerMoveEvent(player: Player, val from: Position, val to: Position) : PlayerEvent(player), Cancellable {
    override var isCancelled: Boolean = false
}

/** The player right-clicked holding [item] (hotbar slot [slot]). Cancel to suppress the server's own reaction. */
class PlayerUseItemEvent(player: Player, val item: Item?, val slot: Int) : PlayerEvent(player), Cancellable {
    override var isCancelled: Boolean = false
}

/** A custom payload from the client. */
class PlayerPluginMessageEvent(player: Player, val channel: String, val data: ByteArray) : PlayerEvent(player)
