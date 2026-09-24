package ru.cororo.authserver.api.command

import net.kyori.adventure.audience.Audience
import ru.cororo.authserver.api.plugin.Plugin

/** Who ran a command: a [ru.cororo.authserver.api.player.Player] or the console. */
interface CommandSource : Audience {
    val name: String
}

fun interface CommandExecutor {
    /** @param arguments whitespace-separated arguments after the command name */
    fun execute(source: CommandSource, arguments: List<String>)
}

/**
 * A command. Players see it in completion (1.13+) when they are allowed to use it.
 *
 * @property usage argument syntax shown in completion, e.g. `<password> <password>`; a last argument written
 *   `<name...>` takes the rest of the line, spaces included, so clients accept it
 * @property availableBeforeLogin whether unauthenticated players may run it (login and register commands)
 * @property playersOnly console use is refused
 * @property consoleOnly players cannot run it at all (administration commands)
 */
data class Command(
    val name: String,
    val aliases: List<String> = emptyList(),
    val usage: String = "",
    val description: String = "",
    val availableBeforeLogin: Boolean = false,
    val playersOnly: Boolean = false,
    val consoleOnly: Boolean = false,
    val executor: CommandExecutor,
)

interface CommandManager {
    /** @throws IllegalArgumentException if the name or an alias is taken */
    fun register(plugin: Plugin, command: Command)

    fun unregister(name: String)

    /** Runs a command line (without a leading slash) as [source]; returns `false` if the command is unknown. */
    fun dispatch(source: CommandSource, commandLine: String): Boolean
}
