package ru.cororo.authserver.server.network.handler

import io.netty.buffer.Unpooled
import io.netty.util.concurrent.ScheduledFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import ru.cororo.authserver.api.event.PlayerChatEvent
import ru.cororo.authserver.api.event.PlayerCommandEvent
import ru.cororo.authserver.api.event.PlayerJoinEvent
import ru.cororo.authserver.api.event.PlayerMoveEvent
import ru.cororo.authserver.api.event.PlayerPluginMessageEvent
import ru.cororo.authserver.api.event.PlayerQuitEvent
import ru.cororo.authserver.api.event.PlayerUseItemEvent
import ru.cororo.authserver.api.inventory.MenuClick
import ru.cororo.authserver.api.player.Position
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.protocol.Packet
import ru.cororo.authserver.protocol.ProtocolState
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.buffer.writeString
import ru.cororo.authserver.protocol.packet.ClientInformationPacket
import ru.cororo.authserver.protocol.packet.ClientboundKeepAlivePacket
import ru.cororo.authserver.protocol.packet.ClientboundPluginMessagePacket
import ru.cororo.authserver.protocol.packet.ServerboundKeepAlivePacket
import ru.cororo.authserver.protocol.packet.ServerboundPluginMessagePacket
import ru.cororo.authserver.protocol.packet.UpdateTagsPacket
import ru.cororo.authserver.protocol.packet.play.*
import ru.cororo.authserver.server.AuthServerImpl
import ru.cororo.authserver.server.config.LimboGameMode
import ru.cororo.authserver.server.network.Connection
import ru.cororo.authserver.server.network.PacketHandler
import ru.cororo.authserver.server.player.LimboPlayer
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The limbo: joins the player into the world, keeps the connection alive and turns input into commands. */
class PlayHandler(
    private val server: AuthServerImpl,
    private val connection: Connection,
    private val player: LimboPlayer,
) : PacketHandler {
    private val version = connection.version
    private val data = GameData.version(version)
    private val worldConfig = server.config.world
    private var keepAliveTask: ScheduledFuture<*>? = null
    private var pendingKeepAlive: Long? = null
    private var keepAliveSentAt = 0L

    /** Switches the connection to play and sends everything a client needs to spawn. */
    fun start() {
        connection.switchState(ProtocolState.PLAY, this)
        val spawn = server.world.spawn
        val (centerX, centerZ) = server.world.chunkCenter
        connection.send(JoinGamePacket(
            entityId = ENTITY_ID,
            gameMode = GameMode.valueOf(worldConfig.gameMode.name),
            dimension = data.dimension,
            maxPlayers = server.config.status.maxPlayers,
            viewDistance = worldConfig.viewDistance,
            simulationDistance = worldConfig.viewDistance,
            isFlat = false,
        ))
        if (version < ProtocolVersion.MINECRAFT_1_20_2) data.tags?.let { connection.send(UpdateTagsPacket(it)) }
        if (version < ProtocolVersion.MINECRAFT_1_20_2) connection.send(ClientboundPluginMessagePacket(brandChannel(version), brand()))
        connection.send(PlayerAbilitiesPacket(
            invulnerable = true,
            flying = worldConfig.gameMode == LimboGameMode.SPECTATOR,
            allowFlying = worldConfig.gameMode == LimboGameMode.SPECTATOR || worldConfig.gameMode == LimboGameMode.CREATIVE,
            creativeMode = worldConfig.gameMode == LimboGameMode.CREATIVE,
        ))
        player.refreshCommands = ::sendCommands
        sendCommands()
        connection.send(SpawnPositionPacket(spawn.blockX, spawn.blockY, spawn.blockZ, spawn.yaw, spawn.pitch))
        connection.send(SetTimePacket(worldAge = 0, timeOfDay = worldConfig.timeOfDay, ticking = false, clockId = data.clockId))
        if (version >= ProtocolVersion.MINECRAFT_1_14) connection.send(ChunkCacheCenterPacket(centerX, centerZ))
        server.world.chunkPackets(version).forEach(connection::send)
        if (version >= ProtocolVersion.MINECRAFT_1_20_3) connection.send(GameEventPacket(GameEventPacket.START_WAITING_FOR_CHUNKS))
        player.teleport(spawn)
        player.sendInventory()
        connection.send(SetHeldSlotPacket(0))
        keepAliveTask = connection.channel.eventLoop().scheduleAtFixedRate(::keepAlive, 1, 1, TimeUnit.SECONDS)
        player.launch {
            server.events.post(PlayerJoinEvent(player))
            server.auth.onJoin(player)
        }
    }

    /** Resends the command tree, e.g. after the player authenticated and more commands became available. */
    fun sendCommands() {
        if (version >= ProtocolVersion.MINECRAFT_1_13) connection.send(CommandsPacket(server.commandTree(player), data.stringArgumentParser))
    }

    override fun handle(packet: Packet) {
        when (packet) {
            is TeleportConfirmPacket -> if (packet.teleportId == player.pendingTeleport) player.pendingTeleport = null
            is ServerboundKeepAlivePacket -> if (packet.id == pendingKeepAlive) pendingKeepAlive = null
            is MovePositionPacket -> move(packet.x, packet.y, packet.z, player.position.yaw, player.position.pitch)
            is MovePositionRotationPacket -> move(packet.x, packet.y, packet.z, packet.yaw, packet.pitch)
            is MoveRotationPacket -> player.position = player.position.copy(yaw = packet.yaw, pitch = packet.pitch)
            is ChatPacket -> if (packet.message.startsWith("/")) command(packet.message.drop(1)) else chat(packet.message)
            is ChatCommandPacket -> command(packet.command)
            is SignedChatCommandPacket -> command(packet.command)
            is ClientInformationPacket -> player.locale = locale(packet.locale)
            is PingRequestPacket -> connection.send(PongResponsePacket(packet.payload))
            is SetCarriedItemPacket -> if (packet.slot in 0..8) player.heldSlot = packet.slot
            is ClickContainerPacket -> player.launch { click(packet) }
            is ServerboundCloseContainerPacket -> player.launch { player.menuClosed(packet.windowId) }
            is UseItemPacket -> if (packet.hand == MAIN_HAND) useItem()
            is UseItemOnPacket -> if (packet.hand == MAIN_HAND) useItem()
            is ServerboundPluginMessagePacket -> if (server.events.hasSubscribers(PlayerPluginMessageEvent::class.java)) {
                player.launch { server.events.post(PlayerPluginMessageEvent(player, packet.channel, packet.data)) }
            }
            else -> Unit
        }
    }

    private fun move(x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        // Movement before the client confirmed our teleport refers to the old position.
        if (player.pendingTeleport != null || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val from = player.position
        val to = Position(x, y, z, yaw, pitch)
        val spawn = server.world.spawn
        when {
            y < worldConfig.voidY -> return player.teleport(spawn)
            worldConfig.maxDistance > 0 && to.distanceSquared(spawn) > worldConfig.maxDistance.toDouble() * worldConfig.maxDistance ->
                return player.teleport(from)
        }
        player.position = to
        if (server.events.hasSubscribers(PlayerMoveEvent::class.java)) {
            player.launch {
                if (server.events.post(PlayerMoveEvent(player, from, to)).isCancelled) player.teleport(from)
            }
        }
    }

    /** Runs the clicked slot's handler, then resends everything so no item ever moves. */
    private fun click(packet: ClickContainerPacket) {
        val menu = player.openMenu
        if (menu != null && packet.windowId == player.openMenuWindow && packet.slot in 0 until menu.size) {
            menu.handler(packet.slot)?.onClick(MenuClick(player, menu, packet.slot, menu.item(packet.slot), packet.button == 1))
        }
        if (player.openMenu === menu) player.sendMenu()
        player.sendInventory()
    }

    private fun useItem() {
        val now = System.nanoTime()
        if (now - player.lastUseAt < USE_DEBOUNCE_NANOS) return
        player.lastUseAt = now
        player.launch {
            val slot = player.heldSlot
            val event = server.events.post(PlayerUseItemEvent(player, player.hotbarItem(slot), slot))
            if (!event.isCancelled) server.loginInterface.onUse(player, slot)
        }
    }

    private fun command(line: String) = player.launch {
        val event = server.events.post(PlayerCommandEvent(player, line))
        if (event.isCancelled) return@launch
        if (!server.commands.dispatch(player, event.command)) player.sendMessage(server.messages.render(player.locale, "unknown-command"))
    }

    private fun chat(message: String) = player.launch {
        if (!player.isAuthenticated) return@launch player.sendMessage(server.messages.render(player.locale, "chat-login-first"))
        server.events.post(PlayerChatEvent(player, message))
    }

    private fun keepAlive() {
        val now = System.nanoTime()
        if (pendingKeepAlive != null) {
            if (now - keepAliveSentAt > TimeUnit.SECONDS.toNanos(KEEP_ALIVE_TIMEOUT)) connection.disconnect(Component.text("Timed out"))
            return
        }
        if (now - keepAliveSentAt < TimeUnit.SECONDS.toNanos(KEEP_ALIVE_INTERVAL)) return
        // Pre-1.12.2 keep-alive IDs are var ints.
        val id = now and Int.MAX_VALUE.toLong()
        pendingKeepAlive = id
        keepAliveSentAt = now
        connection.send(ClientboundKeepAlivePacket(id))
    }

    override fun disconnected() {
        keepAliveTask?.cancel(false)
        server.removePlayer(player)
        server.scope.launch(Dispatchers.IO) { server.events.post(PlayerQuitEvent(player)) }
    }

    companion object {
        private const val ENTITY_ID = 1
        private const val KEEP_ALIVE_INTERVAL = 10L
        private const val KEEP_ALIVE_TIMEOUT = 30L
        private const val MAIN_HAND = 0
        private val USE_DEBOUNCE_NANOS = TimeUnit.MILLISECONDS.toNanos(150)

        fun brandChannel(version: ProtocolVersion) = if (version >= ProtocolVersion.MINECRAFT_1_13) "minecraft:brand" else "MC|Brand"

        fun brand(): ByteArray = Unpooled.buffer().run {
            writeString("AuthServer")
            ByteArray(readableBytes()).also(::readBytes)
        }

        fun locale(tag: String): Locale = Locale.forLanguageTag(tag.replace('_', '-'))
    }
}
