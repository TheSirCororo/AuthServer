package ru.cororo.authserver.api.inventory

import net.kyori.adventure.text.Component
import ru.cororo.authserver.api.player.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * An item, named as in the newest supported release (`minecraft:compass`). Older clients get the closest item
 * they know, so any item can be used on every version.
 */
data class Item(
    val type: String,
    val amount: Int = 1,
    val name: Component? = null,
    val lore: List<Component> = emptyList(),
) {
    init {
        require(amount in 1..64) { "Amount must be 1-64" }
    }
}

/** A click on a menu slot. */
data class MenuClick(val player: Player, val menu: Menu, val slot: Int, val item: Item?, val rightClick: Boolean)

fun interface MenuClickHandler {
    fun onClick(click: MenuClick)
}

/**
 * A chest menu of [rows] rows. Players cannot take items out of it; clicks go to the slot's handler.
 * Changing a menu while it is open does not refresh it: open it again.
 */
class Menu(val title: Component, val rows: Int = 3) {
    private val slots = ConcurrentHashMap<Int, Pair<Item, MenuClickHandler?>>()

    /** Called when the player closes the menu themselves (not when another menu replaces it). */
    @Volatile
    var onClose: ((Player) -> Unit)? = null

    init {
        require(rows in 1..6) { "A menu has 1 to 6 rows" }
    }

    val size: Int get() = rows * 9

    fun set(slot: Int, item: Item, onClick: MenuClickHandler? = null): Menu {
        require(slot in 0 until size) { "Slot $slot is outside the menu" }
        slots[slot] = item to onClick
        return this
    }

    fun clear(slot: Int): Menu {
        slots.remove(slot)
        return this
    }

    fun item(slot: Int): Item? = slots[slot]?.first

    fun handler(slot: Int): MenuClickHandler? = slots[slot]?.second
}
