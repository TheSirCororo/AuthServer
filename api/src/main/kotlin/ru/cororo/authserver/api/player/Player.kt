package ru.cororo.authserver.api.player

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.identity.Identified
import net.kyori.adventure.text.Component
import ru.cororo.authserver.api.auth.AuthState
import ru.cororo.authserver.api.command.CommandSource
import ru.cororo.authserver.api.inventory.Item
import ru.cororo.authserver.api.inventory.Menu
import ru.cororo.authserver.bridge.AuthMode
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.packet.ProfileProperty
import java.net.InetSocketAddress
import java.util.Locale
import java.util.UUID

/**
 * A player in the limbo. Chat messages, titles, action bars, boss bars and the tab list header work on every
 * supported client version through the [Audience] methods.
 */
interface Player : Audience, Identified, CommandSource {
    val username: String
    val uniqueId: UUID

    /** Profile properties such as `textures`; empty for offline players unless a proxy forwarded some. */
    val properties: List<ProfileProperty>

    val protocolVersion: ProtocolVersion

    /** Real client address, as forwarded by the proxy when there is one. */
    val address: InetSocketAddress

    /** Client language, known once the client sent its settings; English until then. */
    val locale: Locale

    /** Whether the identity comes from Mojang (licensed) or from a password (offline). */
    val mode: AuthMode

    val authState: AuthState

    val isAuthenticated: Boolean get() = authState == AuthState.AUTHENTICATED

    val position: Position

    fun teleport(position: Position)

    fun kick(reason: Component)

    fun sendPluginMessage(channel: String, data: ByteArray)

    /** Sends the player to another server with the 1.20.5+ transfer packet; returns `false` on older clients. */
    fun transfer(host: String, port: Int): Boolean

    /** Puts [item] into hotbar slot [slot] (0 - 8), or empties it for `null`. */
    fun setHotbarItem(slot: Int, item: Item?)

    fun hotbarItem(slot: Int): Item?

    /** Removes every item from the player's inventory. */
    fun clearInventory()

    /** The hotbar slot the player holds (0 - 8). */
    val heldSlot: Int

    /** The menu the player has open, if any. */
    val openMenu: Menu?

    fun openMenu(menu: Menu)

    fun closeMenu()

    override val name: String get() = username
}

/** A position in the limbo world. */
data class Position(val x: Double, val y: Double, val z: Double, val yaw: Float = 0f, val pitch: Float = 0f) {
    val blockX: Int get() = kotlin.math.floor(x).toInt()
    val blockY: Int get() = kotlin.math.floor(y).toInt()
    val blockZ: Int get() = kotlin.math.floor(z).toInt()

    fun distanceSquared(other: Position): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }
}
