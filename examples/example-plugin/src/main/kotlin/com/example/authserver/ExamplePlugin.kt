package com.example.authserver

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.cororo.authserver.api.command.Command
import ru.cororo.authserver.api.command.CommandExecutor
import ru.cororo.authserver.api.event.PlayerAuthFailedEvent
import ru.cororo.authserver.api.event.PlayerAuthenticatedEvent
import ru.cororo.authserver.api.event.PlayerJoinEvent
import ru.cororo.authserver.api.event.PlayerRegisterEvent
import ru.cororo.authserver.api.event.PlayerUseItemEvent
import ru.cororo.authserver.api.event.on
import ru.cororo.authserver.api.inventory.Item
import ru.cororo.authserver.api.inventory.Menu
import ru.cororo.authserver.api.inventory.MenuClickHandler
import ru.cororo.authserver.api.plugin.AuthServerPlugin
import ru.cororo.authserver.api.plugin.Plugin
import ru.cororo.authserver.bridge.AuthMode
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tour of the plugin API:
 * - greets players and gives them a "Rules" book that opens a menu;
 * - refuses registrations of names starting with "admin";
 * - logs every successful and failed authentication and sends licensed players to a "premium" server;
 * - adds `/hello` (usable before logging in) and a console `stats` command, and logs statistics periodically.
 *
 * The annotation replaces a hand-written `authserver-plugin.json`: KSP generates it at build time.
 */
@AuthServerPlugin(
    id = "example",
    name = "Example",
    version = "1.0.0",
    description = "Shows the AuthServer plugin API: events, commands, items, menus and the scheduler",
    authors = ["cororo"],
)
class ExamplePlugin : Plugin() {
    private val authenticated = AtomicInteger()
    private val failed = AtomicInteger()

    override fun onEnable() {
        server.events.on<PlayerJoinEvent>(this) { event ->
            event.player.sendMessage(Component.text("Welcome, ${event.player.username}!", NamedTextColor.AQUA))
            event.player.setHotbarItem(RULES_SLOT, Item("minecraft:written_book", name = Component.text("Rules", NamedTextColor.GOLD)))
        }

        server.events.on<PlayerRegisterEvent>(this) { event ->
            if (event.player.username.startsWith("admin", ignoreCase = true)) {
                event.isCancelled = true
                event.reason = Component.text("Names starting with 'admin' are reserved.", NamedTextColor.RED)
            }
        }

        server.events.on<PlayerAuthenticatedEvent>(this) { event ->
            authenticated.incrementAndGet()
            logger.info("{} logged in ({}, {})", event.player.username, event.mode, event.method)
            // Behind Velocity this overrides the proxy plugin's routing for this player.
            if (event.mode == AuthMode.ONLINE) event.targetServer = "premium"
        }

        server.events.on<PlayerAuthFailedEvent>(this) { event ->
            failed.incrementAndGet()
            logger.info("{} failed to log in: {}", event.player.username, event.reason)
        }

        server.events.on<PlayerUseItemEvent>(this) { event ->
            if (event.slot == RULES_SLOT) {
                event.isCancelled = true
                event.player.openMenu(rulesMenu())
            }
        }

        server.commands.register(this, Command(
            name = "hello",
            description = "Says hello",
            availableBeforeLogin = true,
            executor = CommandExecutor { source, arguments ->
                source.sendMessage(Component.text("Hello, ${arguments.firstOrNull() ?: source.name}!", NamedTextColor.GREEN))
            },
        ))
        server.commands.register(this, Command(name = "stats", consoleOnly = true, executor = CommandExecutor { source, _ ->
            source.sendMessage(Component.text(statistics()))
        }))

        server.scheduler.runRepeating(this, Duration.ofMinutes(5), Duration.ofMinutes(5)) { logger.info(statistics()) }
        logger.info("Example plugin enabled")
    }

    private fun rulesMenu() = Menu(Component.text("Server rules"), rows = 1).apply {
        RULES.forEachIndexed { slot, rule ->
            set(slot, Item("minecraft:paper", name = Component.text("Rule ${slot + 1}", NamedTextColor.YELLOW), lore = listOf(Component.text(rule))),
                MenuClickHandler { click -> click.player.sendMessage(Component.text("Rule ${slot + 1}: $rule")) })
        }
        set(8, Item("minecraft:barrier", name = Component.text("Close", NamedTextColor.RED)), MenuClickHandler { it.player.closeMenu() })
    }

    private fun statistics() = "${server.players.size} players waiting, $authenticated logins, $failed failures since start"

    private companion object {
        const val RULES_SLOT = 8
        val RULES = listOf("Be kind to others.", "No cheating.", "Have fun!")
    }
}
