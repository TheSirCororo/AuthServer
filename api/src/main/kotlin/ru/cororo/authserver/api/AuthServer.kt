package ru.cororo.authserver.api

import net.kyori.adventure.audience.ForwardingAudience
import ru.cororo.authserver.api.auth.AuthService
import ru.cororo.authserver.api.command.CommandManager
import ru.cororo.authserver.api.event.EventManager
import ru.cororo.authserver.api.player.Player
import ru.cororo.authserver.api.plugin.PluginManager
import ru.cororo.authserver.api.scheduler.Scheduler
import ru.cororo.authserver.api.world.LimboWorld
import java.util.UUID

/**
 * The running auth server. Plugins receive it through [ru.cororo.authserver.api.plugin.Plugin.server].
 * As a [ForwardingAudience] it broadcasts to every player in the limbo.
 */
interface AuthServer : ForwardingAudience {
    /** Implementation version, e.g. `0.2.0`. */
    val version: String

    /** Whether the server sits behind a proxy (modern or legacy forwarding) instead of accepting clients directly. */
    val proxied: Boolean

    /** Players in the limbo, authenticated or not. */
    val players: Collection<Player>

    fun player(name: String): Player?

    fun player(uniqueId: UUID): Player?

    val auth: AuthService
    val events: EventManager
    val commands: CommandManager
    val scheduler: Scheduler
    val plugins: PluginManager
    val world: LimboWorld

    override fun audiences(): Iterable<Player> = players
}
