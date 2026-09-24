package ru.cororo.authserver.server.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.identity.Identity
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.slf4j.LoggerFactory
import net.kyori.adventure.text.format.TextDecoration
import ru.cororo.authserver.api.auth.AuthState
import ru.cororo.authserver.api.inventory.Item
import ru.cororo.authserver.api.inventory.Menu
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.protocol.buffer.ItemStack
import ru.cororo.authserver.protocol.packet.play.CloseContainerPacket
import ru.cororo.authserver.protocol.packet.play.ContainerContentPacket
import ru.cororo.authserver.protocol.packet.play.ContainerSlotPacket
import ru.cororo.authserver.protocol.packet.play.OpenScreenPacket
import ru.cororo.authserver.api.player.Player
import ru.cororo.authserver.api.player.Position
import ru.cororo.authserver.bridge.AuthMode
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.packet.ClientboundPluginMessagePacket
import ru.cororo.authserver.protocol.packet.ProfileProperty
import ru.cororo.authserver.protocol.packet.TransferPacket
import ru.cororo.authserver.protocol.packet.play.ActionBarPacket
import ru.cororo.authserver.protocol.packet.play.BossBarPacket
import ru.cororo.authserver.protocol.packet.play.ClearTitlesPacket
import ru.cororo.authserver.protocol.packet.play.PlayerPositionPacket
import ru.cororo.authserver.protocol.packet.play.SubtitleTextPacket
import ru.cororo.authserver.protocol.packet.play.SystemChatPacket
import ru.cororo.authserver.protocol.packet.play.TabListPacket
import ru.cororo.authserver.protocol.packet.play.TitleTextPacket
import ru.cororo.authserver.protocol.packet.play.TitleTimesPacket
import ru.cororo.authserver.server.network.Connection
import java.net.InetSocketAddress
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A player in the limbo. Game logic for the player runs sequentially in [scope] (off the network threads);
 * packets may be sent from any thread.
 */
class LimboPlayer(
    private val connection: Connection,
    override val username: String,
    override val uniqueId: UUID,
    override val properties: List<ProfileProperty>,
    override val mode: AuthMode,
    spawn: Position,
) : Player {
    /** Sequential executor for this player's logic: at most one task runs at a time. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    override val protocolVersion: ProtocolVersion get() = connection.version
    override val address: InetSocketAddress get() = connection.address

    @Volatile
    override var locale: Locale = Locale.ENGLISH

    @Volatile
    override var authState: AuthState = AuthState.UNAUTHENTICATED

    @Volatile
    override var position: Position = spawn
        internal set

    /** Wrong passwords so far in this connection. */
    var failedAttempts = 0

    /** Jobs that remind and time the player out until authentication. */
    val authJobs = mutableListOf<Job>()

    /** Resends the command tree; set by the play handler once the player is in the world. */
    @Volatile
    var refreshCommands: () -> Unit = {}

    /** Whether an account with this name existed when the player joined. */
    @Volatile
    var registered = false

    /** Whether the login menu may still offer a licensed login. */
    @Volatile
    var canChooseLicensed = false

    /** Set once `/premium` asked for confirmation. */
    var premiumConfirmationPending = false

    private val teleportIds = AtomicInteger()

    @Volatile
    var pendingTeleport: Int? = null

    private val bossBars = ConcurrentHashMap<BossBar, UUID>()
    private val bossBarListener = object : BossBar.Listener {
        override fun bossBarNameChanged(bar: BossBar, oldName: Component, newName: Component) =
            sendBossBar(bar, BossBarPacket.Operation.UpdateTitle(newName))

        override fun bossBarProgressChanged(bar: BossBar, oldProgress: Float, newProgress: Float) =
            sendBossBar(bar, BossBarPacket.Operation.UpdateProgress(newProgress))
    }

    val isOnline: Boolean get() = connection.isActive

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try {
            block()
        } catch (exception: Exception) {
            if (exception is kotlinx.coroutines.CancellationException) throw exception
            logger.error("Error while handling {}", username, exception)
        }
    }

    override fun identity(): Identity = Identity.identity(uniqueId)

    override fun sendMessage(message: Component) = connection.send(SystemChatPacket(message))

    override fun sendActionBar(message: Component) = connection.send(ActionBarPacket(message))

    override fun sendPlayerListHeaderAndFooter(header: Component, footer: Component) = connection.send(TabListPacket(header, footer))

    override fun showTitle(title: Title) {
        title.times()?.let { times ->
            connection.send(TitleTimesPacket(ticks(times.fadeIn()), ticks(times.stay()), ticks(times.fadeOut())))
        }
        connection.send(SubtitleTextPacket(title.subtitle()))
        connection.send(TitleTextPacket(title.title()))
    }

    override fun clearTitle() = connection.send(ClearTitlesPacket(reset = false))

    override fun resetTitle() = connection.send(ClearTitlesPacket(reset = true))

    override fun showBossBar(bar: BossBar) {
        if (bossBars.putIfAbsent(bar, UUID.randomUUID()) != null) return
        bar.addListener(bossBarListener)
        sendBossBar(bar, BossBarPacket.Operation.Add(
            bar.name(), bar.progress(),
            BossBarPacket.Color.valueOf(bar.color().name), BossBarPacket.Overlay.entries[bar.overlay().ordinal],
        ))
    }

    override fun hideBossBar(bar: BossBar) {
        val id = bossBars.remove(bar) ?: return
        bar.removeListener(bossBarListener)
        connection.send(BossBarPacket(id, BossBarPacket.Operation.Remove))
    }

    private fun sendBossBar(bar: BossBar, operation: BossBarPacket.Operation) {
        val id = bossBars[bar] ?: return
        connection.send(BossBarPacket(id, operation))
    }

    override fun kick(reason: Component) = connection.disconnect(reason)

    override fun teleport(position: Position) {
        val id = teleportIds.incrementAndGet()
        pendingTeleport = id
        this.position = position
        connection.send(PlayerPositionPacket(position.x, position.y, position.z, position.yaw, position.pitch, id))
    }

    override fun sendPluginMessage(channel: String, data: ByteArray) = connection.send(ClientboundPluginMessagePacket(channel, data))

    // ------------------------------------------------------------------------------------------------ inventory

    private val hotbar = arrayOfNulls<Item>(9)

    @Volatile
    override var heldSlot: Int = 0
        internal set

    @Volatile
    override var openMenu: Menu? = null
        private set

    /** Window ID of [openMenu]; vanilla counts 1 - 100. */
    @Volatile
    var openMenuWindow = 0
        private set
    private var nextWindow = 0

    /** Time of the last item use, to merge the use-on-block and use-in-air packets one click can produce. */
    @Volatile
    var lastUseAt = 0L

    override fun setHotbarItem(slot: Int, item: Item?) {
        require(slot in 0..8) { "Hotbar slots are 0-8" }
        hotbar[slot] = item
        connection.send(ContainerSlotPacket(PLAYER_WINDOW, HOTBAR_START + slot, item?.let(::stack)))
    }

    override fun hotbarItem(slot: Int): Item? = hotbar.getOrNull(slot)

    override fun clearInventory() {
        hotbar.fill(null)
        sendInventory()
    }

    /** The whole player inventory window: 45 slots on 1.8, 46 (with the off hand) since 1.9. */
    fun sendInventory() {
        val size = if (protocolVersion >= ProtocolVersion.MINECRAFT_1_9) 46 else 45
        val items = MutableList<ItemStack?>(size) { null }
        hotbar.forEachIndexed { slot, item -> items[HOTBAR_START + slot] = item?.let(::stack) }
        connection.send(ContainerContentPacket(PLAYER_WINDOW, items))
    }

    override fun openMenu(menu: Menu) {
        nextWindow = nextWindow % 100 + 1
        openMenu = menu
        openMenuWindow = nextWindow
        connection.send(OpenScreenPacket(openMenuWindow, menu.rows, menu.title))
        sendMenu()
    }

    /** Resends the open menu's contents and an empty cursor, undoing whatever the client predicted locally. */
    fun sendMenu() {
        val menu = openMenu ?: return
        connection.send(ContainerContentPacket(openMenuWindow, List(menu.size) { menu.item(it)?.let(::stack) }))
        connection.send(ContainerSlotPacket(CURSOR_WINDOW, CURSOR_SLOT, null))
    }

    override fun closeMenu() {
        if (openMenu == null) return
        connection.send(CloseContainerPacket(openMenuWindow))
        openMenu = null
    }

    /** The client closed the menu itself. */
    fun menuClosed(windowId: Int) {
        val menu = openMenu?.takeIf { windowId == openMenuWindow } ?: return
        openMenu = null
        menu.onClose?.invoke(this)
    }

    /** Converts an item to the client version's IDs; unknown item types fall back to stone. */
    private fun stack(item: Item): ItemStack {
        val canonical = GameData.item(item.type) ?: GameData.item("minecraft:stone")!!
        fun plain(text: Component) = text.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
        return ItemStack(GameData.version(protocolVersion).item(canonical), item.amount, item.name?.let(::plain), item.lore.map(::plain))
    }

    override fun transfer(host: String, port: Int): Boolean {
        if (protocolVersion < ProtocolVersion.MINECRAFT_1_20_5) return false
        connection.send(TransferPacket(host, port))
        return true
    }

    /** Releases listeners and stops the player's logic after the connection closed. */
    fun dispose() {
        bossBars.keys.forEach { it.removeListener(bossBarListener) }
        bossBars.clear()
        scope.cancel()
    }

    override fun toString() = "LimboPlayer($username, $mode, $protocolVersion)"

    private fun ticks(duration: java.time.Duration) = (duration.toMillis() / 50).toInt()

    private companion object {
        val logger = LoggerFactory.getLogger(LimboPlayer::class.java)
        const val PLAYER_WINDOW = 0
        const val HOTBAR_START = 36
        const val CURSOR_WINDOW = -1
        const val CURSOR_SLOT = -1
    }
}
