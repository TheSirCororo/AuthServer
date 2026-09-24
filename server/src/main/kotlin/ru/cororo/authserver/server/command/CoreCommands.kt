package ru.cororo.authserver.server.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.cororo.authserver.api.auth.AuthResult
import ru.cororo.authserver.api.command.Command
import ru.cororo.authserver.api.command.CommandExecutor
import ru.cororo.authserver.api.command.CommandSource
import ru.cororo.authserver.bridge.AuthMode
import ru.cororo.authserver.server.AuthServerImpl
import ru.cororo.authserver.server.player.LimboPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.cororo.authserver.storage.importer.ImportOptions
import ru.cororo.authserver.storage.importer.ImportSource
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

/** Player authentication commands and console administration commands. */
class CoreCommands(private val server: AuthServerImpl) {
    private val auth = server.auth

    fun register() {
        player("register", listOf("reg"), "<password> <password>", beforeLogin = true) { player, args ->
            if (args.size != 2) return@player usage(player, "/register <password> <password>")
            auth.register(player, args[0], args[1])
        }
        player("login", listOf("l", "log"), "<password>", beforeLogin = true) { player, args ->
            if (args.size != 1) return@player usage(player, "/login <password>")
            auth.login(player, args[0])
        }
        player("changepassword", listOf("changepass", "cp"), "<old> <new>") { player, args ->
            if (args.size != 2) return@player usage(player, "/changepassword <old> <new>")
            auth.changePassword(player, args[0], args[1])
        }
        player("unregister", listOf("unreg"), "<password>") { player, args ->
            if (args.size != 1) return@player usage(player, "/unregister <password>")
            auth.unregister(player, args[0])
        }
        // Behind a proxy the plugin learns about licensed accounts through the HTTP API, so /premium needs it.
        if (server.config.authentication.licensedLogin && (!server.proxied || server.config.api.enabled)) {
            // Before login it only offers new players the licensed login; accounts must log in to switch.
            player("premium", emptyList(), "[confirm]", beforeLogin = true) { player, args ->
                auth.enablePremium(player, args.firstOrNull() == "confirm", "premium")
            }
        }
        // Authenticator apps show codes as "123 456"; players type them that way.
        player("code", emptyList(), "<code...>", beforeLogin = true) { player, args ->
            auth.submitCode(player, args.joinToString("").ifEmpty { null })
        }
        // Authenticated players manage their account security; elsewhere on the proxy the plugin offers the same.
        player("2fa", listOf("twofactor"), "[totp|email|confirm|off] [code...]") { player, args ->
            server.security.twoFactorCommand(player, args)
        }
        if (server.config.email.enabled) {
            player("email", listOf("mail"), "[set|confirm|remove] [address|code|password] [password]") { player, args ->
                server.security.emailCommand(player, args)
            }
            player("recover", listOf("recovery", "restore"), "[code] [new password] [new password]", beforeLogin = true) { player, args ->
                auth.recover(player, args)
            }
        }
        // Exiting runs the shutdown hook, which stops the server cleanly.
        console("stop", "", "Stops the server") { _, _ -> Thread { exitProcess(0) }.start() }
        console("list", "", "Lists players in the limbo") { source, _ ->
            source.info("${server.players.size} players: " + server.players.joinToString { "${it.username} (${it.mode}, ${it.authState})" })
        }
        console("kick", "<player> [reason]", "Disconnects a player") { source, args ->
            val player = args.firstOrNull()?.let(server::player) ?: return@console source.info("No such player")
            player.kick(Component.text(args.drop(1).joinToString(" ").ifBlank { "Kicked" }))
        }
        console("plugins", "", "Lists plugins") { source, _ ->
            source.info(server.plugins.plugins.joinToString { "${it.description.name} ${it.description.version}" }.ifEmpty { "No plugins" })
        }
        console("auth", AUTH_USAGE, "Manages accounts") { source, args ->
            val name = args.getOrNull(1) ?: return@console source.info("Usage: auth $AUTH_USAGE")
            when (args[0]) {
                "info" -> auth.account(name).thenAccept { source.info(it?.toString() ?: "No account named $name") }
                "register" -> args.getOrNull(2)?.let { report(source, auth.register(name, it)) } ?: source.info("Usage: auth register <name> <password>")
                "password" -> args.getOrNull(2)?.let { report(source, auth.changePassword(name, it)) } ?: source.info("Usage: auth password <name> <password>")
                "unregister" -> report(source, auth.unregister(name))
                "premium" -> when (args.getOrNull(2)) {
                    "on" -> report(source, auth.setPremium(name, true))
                    "off" -> report(source, auth.setPremium(name, false))
                    else -> source.info("Usage: auth premium <name> <on|off>")
                }
                // For players who lost their authenticator or mailbox.
                "2fa" -> if (args.getOrNull(2) == "off") {
                    server.scope.launch { source.info(if (server.security.disableTwoFactor(name)) "Two-factor authentication of $name is off" else "No account named $name") }
                } else source.info("Usage: auth 2fa <name> off")
                "email" -> args.getOrNull(2)?.let { value ->
                    server.scope.launch {
                        val done = server.security.setEmail(name, value.takeUnless { it == "off" })
                        source.info(if (done) "Email of $name updated" else "No account named $name, or not an email address")
                    }
                } ?: source.info("Usage: auth email <name> <address|off>")
                else -> source.info("Unknown action ${args[0]}")
            }
        }
        console("import", IMPORT_USAGE, "Imports accounts from another auth plugin") { source, args -> import(source, args) }
        console("help", "", "Lists console commands") { source, _ ->
            source.info("stop, list, kick <player> [reason], plugins, auth $AUTH_USAGE, import $IMPORT_USAGE")
        }
    }

    /** `import <plugin> <jdbc url or file> [key=value ...] [overwrite] [dry-run] [plaintext]`, run in the background. */
    private fun import(source: CommandSource, args: List<String>) {
        val plugin = args.getOrNull(0)?.let(ImportSource::byName)
        val location = args.getOrNull(1)
        if (plugin == null || location == null) return source.info("Usage: import $IMPORT_USAGE")
        val settings = args.drop(2).filter { '=' in it }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
        val flags = args.drop(2).filter { '=' !in it }.map(String::lowercase).toSet()
        val options = ImportOptions(
            source = plugin,
            location = if (location.startsWith("jdbc:")) location else server.directory.resolve(location).toString(),
            user = settings["user"], password = settings["password"], table = settings["table"],
            nameColumn = settings["name-column"], passwordColumn = settings["password-column"],
            overwrite = "overwrite" in flags, dryRun = "dry-run" in flags, plainTextPasswords = "plaintext" in flags,
        )
        source.info("Importing from $plugin...")
        server.scope.launch(Dispatchers.IO) {
            runCatching { server.importer.run(options) }
                .onSuccess { source.info((if (options.dryRun) "Dry run: " else "Imported: ") + it) }
                .onFailure { source.info("Import failed: ${it.message}") }
        }
    }

    private fun player(
        name: String, aliases: List<String>, usage: String, beforeLogin: Boolean = false,
        action: suspend (LimboPlayer, List<String>) -> Unit,
    ) = server.commands.register(server.core, Command(
        name = name, aliases = aliases, usage = usage, availableBeforeLogin = beforeLogin, playersOnly = true,
        // Commands arrive in the player's scope already; run the step there so it stays sequential.
        executor = CommandExecutor { source, arguments ->
            val player = source as LimboPlayer
            player.launch { action(player, arguments) }
        },
    ))

    private fun console(name: String, usage: String, description: String, action: (CommandSource, List<String>) -> Unit) =
        server.commands.register(server.core, Command(name = name, usage = usage, description = description, consoleOnly = true,
            executor = CommandExecutor(action)))

    private fun usage(player: LimboPlayer, usage: String) =
        player.sendMessage(server.messages.render(player.locale, "usage", "usage" to usage))

    private fun report(source: CommandSource, result: CompletableFuture<AuthResult>) = result.thenAccept {
        source.info(if (it is AuthResult.Failure) "Failed: ${it.reason}" else "Done")
    }

    private companion object {
        const val AUTH_USAGE = "<info|register|password|unregister|premium|2fa|email> <name> [value]"
        const val IMPORT_USAGE = "<authme|limboauth|bungeeauth|simplelogin|authsystem|xlogin|auto> <jdbc-url|file> " +
            "[user=] [password=] [table=] [name-column=] [password-column=] [overwrite] [dry-run] [plaintext]"
    }

    private fun CommandSource.info(text: String) = sendMessage(Component.text(text, NamedTextColor.GRAY))
}
