package ru.cororo.authserver.server.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.cororo.authserver.api.inventory.Item
import ru.cororo.authserver.api.inventory.Menu
import ru.cororo.authserver.api.inventory.MenuClickHandler
import ru.cororo.authserver.bridge.AuthMode
import ru.cororo.authserver.server.config.AuthenticationConfig
import ru.cororo.authserver.server.config.Messages
import ru.cororo.authserver.server.config.PremiumPolicy
import ru.cororo.authserver.server.player.LimboPlayer
import ru.cororo.authserver.storage.AccountExistsException
import ru.cororo.authserver.storage.AccountRepository

/**
 * Item-based help for unauthenticated players: a help item that explains how to log in, and - when both licensed and
 * offline logins are possible for a new name - a menu to choose one.
 */
class LoginInterface(
    private val config: AuthenticationConfig,
    private val messages: Messages,
    private val accounts: AccountRepository,
) {
    /** A new offline player on a server with both login kinds may still pick a licensed login. */
    private fun canChooseLicensed(player: LimboPlayer, registered: Boolean) = config.loginMenu && config.licensedLogin &&
        config.premiumPolicy != PremiumPolicy.ONLINE && player.mode == AuthMode.OFFLINE && !registered

    /** Called when a player has to log in or register. */
    fun onPrompt(player: LimboPlayer, registered: Boolean) {
        player.registered = registered
        player.canChooseLicensed = canChooseLicensed(player, registered)
        if (config.helpItem.isNotBlank()) player.setHotbarItem(HELP_SLOT, helpItem(player))
        if (player.canChooseLicensed) player.openMenu(loginMenu(player))
    }

    /** Right click with the item in [slot]. */
    fun onUse(player: LimboPlayer, slot: Int) {
        if (player.isAuthenticated || config.helpItem.isBlank() || player.hotbarItem(slot)?.type != config.helpItem) return
        if (player.canChooseLicensed) player.openMenu(loginMenu(player)) else instructions(player)
    }

    fun onAuthenticated(player: LimboPlayer) {
        player.closeMenu()
        player.clearInventory()
    }

    private fun instructions(player: LimboPlayer) {
        player.sendMessage(text(player, if (player.registered) "help-login" else "help-register"))
    }

    private fun helpItem(player: LimboPlayer) = Item(config.helpItem, name = text(player, "help-item-name"), lore = listOf(text(player, "help-item-lore")))

    private fun loginMenu(player: LimboPlayer): Menu {
        val menu = Menu(text(player, "menu-title"), rows = 3)
        menu.set(OFFLINE_SLOT, Item("minecraft:writable_book", name = text(player, "menu-offline-name"), lore = listOf(text(player, "menu-offline-lore"))),
            MenuClickHandler { click ->
                player.closeMenu()
                instructions(click.player as LimboPlayer)
            })
        menu.set(LICENSED_SLOT, Item("minecraft:emerald", name = text(player, "menu-licensed-name"), lore = listOf(text(player, "menu-licensed-lore"))),
            MenuClickHandler {
                // Choosing a licensed login cannot be undone by the player, so it takes a second click.
                menu.set(CONFIRM_SLOT, Item("minecraft:lime_wool", name = text(player, "menu-confirm-name"), lore = listOf(text(player, "menu-confirm-lore"))),
                    MenuClickHandler { player.launch { chooseLicensed(player) } })
            })
        menu.onClose = { instructions(player) }
        return menu
    }

    /** Binds the name to a licensed account; the next connection is authenticated through Mojang. */
    private suspend fun chooseLicensed(player: LimboPlayer) {
        try {
            withContext(Dispatchers.IO) { accounts.create(player.username, null, player.address.hostString, premium = true) }
        } catch (_: AccountExistsException) {
            // Registered meanwhile (another connection); the next login follows whatever that account is.
        }
        player.kick(text(player, "kick-licensed-chosen"))
    }

    private fun text(player: LimboPlayer, key: String) = messages.render(player.locale, key)

    private companion object {
        const val HELP_SLOT = 0
        const val OFFLINE_SLOT = 11
        const val CONFIRM_SLOT = 13
        const val LICENSED_SLOT = 15
    }
}
