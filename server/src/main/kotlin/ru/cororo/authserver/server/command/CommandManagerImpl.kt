package ru.cororo.authserver.server.command

import net.kyori.adventure.text.Component
import org.slf4j.LoggerFactory
import ru.cororo.authserver.api.command.Command
import ru.cororo.authserver.api.command.CommandManager
import ru.cororo.authserver.api.command.CommandSource
import ru.cororo.authserver.api.player.Player
import ru.cororo.authserver.api.plugin.Plugin
import ru.cororo.authserver.protocol.packet.play.CommandNode
import ru.cororo.authserver.protocol.packet.play.StringArgumentMode
import java.util.concurrent.ConcurrentHashMap

class CommandManagerImpl(private val messages: (CommandSource, String, Array<out Pair<String, Any>>) -> Component) : CommandManager {
    private val logger = LoggerFactory.getLogger(CommandManagerImpl::class.java)

    private data class Registered(val plugin: Plugin, val command: Command)

    private val commands = ConcurrentHashMap<String, Registered>()

    override fun register(plugin: Plugin, command: Command) {
        val names = (listOf(command.name) + command.aliases).map { it.lowercase() }
        synchronized(commands) {
            names.firstOrNull(commands::containsKey)?.let { throw IllegalArgumentException("Command /$it is already registered") }
            names.forEach { commands[it] = Registered(plugin, command) }
        }
    }

    override fun unregister(name: String) {
        synchronized(commands) {
            val registered = commands[name.lowercase()] ?: return
            commands.values.removeIf { it === registered }
        }
    }

    fun unregisterAll(plugin: Plugin) {
        synchronized(commands) { commands.values.removeIf { it.plugin === plugin } }
    }

    fun command(name: String): Command? = commands[name.lowercase()]?.command

    override fun dispatch(source: CommandSource, commandLine: String): Boolean {
        val parts = commandLine.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val command = parts.firstOrNull()?.let(::command) ?: return false
        when {
            source is Player && command.consoleOnly -> return false
            source is Player && !command.availableBeforeLogin && !source.isAuthenticated -> {
                source.sendMessage(messages(source, "login-first", emptyArray()))
                return true
            }
            source !is Player && command.playersOnly -> {
                source.sendMessage(messages(source, "players-only", emptyArray()))
                return true
            }
        }
        try {
            command.executor.execute(source, parts.drop(1))
        } catch (exception: Exception) {
            logger.error("Command /{} failed for {}", command.name, source.name, exception)
        }
        return true
    }

    /** Completion tree for a player: every command it may use, with its usage split into word arguments. */
    fun tree(player: Player): List<CommandNode> = commands.entries
        .filter { (name, registered) ->
            val command = registered.command
            !command.consoleOnly && (player.isAuthenticated || command.availableBeforeLogin) && name.isNotEmpty()
        }
        .map { (name, registered) -> CommandNode.Literal(name, arguments(registered.command.usage), executable = true) }

    /** Argument nodes for a usage string such as `<password> <password>` or `[action] [code...]`. */
    internal fun arguments(usage: String): List<CommandNode> {
        val names = Regex("<([^>]+)>|\\[([^]]+)]").findAll(usage).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        return names.foldRight(emptyList()) { name, children ->
            // Clients refuse to send what their command tree cannot parse, so `...` marks text with spaces.
            val mode = if (name.endsWith("...") && children.isEmpty()) StringArgumentMode.GREEDY_PHRASE else StringArgumentMode.SINGLE_WORD
            listOf(CommandNode.StringArgument(name.removeSuffix("...").replace(' ', '_'), mode, children, executable = true))
        }
    }
}
