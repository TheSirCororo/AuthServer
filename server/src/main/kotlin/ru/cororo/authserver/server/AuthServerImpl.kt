package ru.cororo.authserver.server

import ru.cororo.authserver.server.mail.SmtpMailSender
import ru.cororo.authserver.server.mail.MailSender
import ru.cororo.authserver.server.auth.security.AccountSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.LoggerFactory
import ru.cororo.authserver.api.AuthServer
import ru.cororo.authserver.api.command.CommandSource
import ru.cororo.authserver.api.event.ServerStartedEvent
import ru.cororo.authserver.api.event.ServerStoppingEvent
import ru.cororo.authserver.api.player.Player
import ru.cororo.authserver.api.plugin.Plugin
import ru.cororo.authserver.api.plugin.PluginDescription
import ru.cororo.authserver.bridge.BridgeCodec
import ru.cororo.authserver.protocol.packet.play.CommandNode
import ru.cororo.authserver.server.auth.AuthManager
import ru.cororo.authserver.server.auth.HttpMojangApi
import ru.cororo.authserver.server.auth.LicensedLoginRequests
import ru.cororo.authserver.server.auth.LoginInterface
import ru.cororo.authserver.server.auth.MojangApi
import ru.cororo.authserver.server.auth.PremiumResolver
import ru.cororo.authserver.server.command.CommandManagerImpl
import ru.cororo.authserver.server.command.Console
import ru.cororo.authserver.server.command.CoreCommands
import ru.cororo.authserver.server.config.ForwardingMode
import ru.cororo.authserver.server.config.Messages
import ru.cororo.authserver.server.config.ServerConfig
import ru.cororo.authserver.server.event.EventManagerImpl
import ru.cororo.authserver.server.http.ProxyApi
import ru.cororo.authserver.server.network.NetworkServer
import ru.cororo.authserver.server.player.LimboPlayer
import ru.cororo.authserver.server.plugin.PluginManagerImpl
import ru.cororo.authserver.server.scheduler.SchedulerImpl
import ru.cororo.authserver.server.world.LimboWorldService
import ru.cororo.authserver.storage.Database
import ru.cororo.authserver.storage.DatabaseConfig
import ru.cororo.authserver.storage.importer.AccountImporter
import ru.cororo.authserver.storage.password.Argon2Settings
import ru.cororo.authserver.storage.password.PasswordHasher
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Wires every service together and owns the server lifecycle. */
class AuthServerImpl(
    val config: ServerConfig,
    val directory: Path,
    val mojang: MojangApi = HttpMojangApi(config.authentication.sessionServer, config.authentication.profileApi),
    mail: MailSender = SmtpMailSender(config.email),
) : AuthServer {
    private val logger = LoggerFactory.getLogger(AuthServerImpl::class.java)

    override val version: String = AuthServerImpl::class.java.`package`?.implementationVersion ?: "development"
    override val proxied: Boolean = config.proxy.forwarding != ForwardingMode.NONE

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val messages = Messages.load(directory.resolve(config.messages.directory), config.messages.defaultLanguage)
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    val motd: Component = MiniMessage.miniMessage().deserialize(config.status.motd)
    val favicon: String? = config.status.favicon.takeIf(String::isNotBlank)?.let { path ->
        "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(directory.resolve(path)))
    }

    private val database = Database(config.database.let {
        DatabaseConfig(it.type, directory.resolve(it.file).toString(), it.host, it.port, it.database, it.user, it.password, it.poolSize, it.tablePrefix)
    })
    private val hasher = PasswordHasher(config.authentication.hashing.let { Argon2Settings(it.memoryKib, it.iterations, it.parallelism) })
    private val hashing = Dispatchers.Default.limitedParallelism(config.authentication.hashing.threads.coerceAtLeast(1))
    val security = AccountSecurity(config.email, config.twoFactor, database.accounts, hasher, hashing, messages, mail)
    val importer = AccountImporter(database.accounts, hasher)
    private val licensedRequests = LicensedLoginRequests()
    val premium = PremiumResolver(config.authentication.licensedLogin, config.authentication.premiumPolicy, database.accounts, mojang, licensedRequests)

    override val events = EventManagerImpl()
    override val scheduler = SchedulerImpl(scope)
    override val commands = CommandManagerImpl { source, key, placeholders -> messages.render(localeOf(source), key, *placeholders) }
    override val plugins = PluginManagerImpl(directory.resolve("plugins")) { plugin ->
        events.unsubscribeAll(plugin)
        scheduler.cancelAll(plugin)
        commands.unregisterAll(plugin)
    }
    override val world = LimboWorldService(config.world, directory)
    // The menu's licensed choice is handled by `auth`, which in turn drives the interface.
    val loginInterface = LoginInterface(config.authentication, messages) { player -> auth.requestLicensedLogin(player) }
    override val auth: AuthManager = AuthManager(
        config = config.authentication,
        accounts = database.accounts,
        hasher = hasher,
        messages = messages,
        events = events,
        bridge = if (proxied && config.proxy.secret.isNotEmpty()) BridgeCodec(config.proxy.secret) else null,
        transfer = config.transfer,
        loginInterface = loginInterface,
        licensedRequests = licensedRequests,
        security = security,
        hashing = hashing,
        commandsChanged = { it.refreshCommands() },
        scope = scope,
    )

    /** Owner of built-in commands; never disabled. */
    val core: Plugin = object : Plugin() {}.also {
        it.initialize(this, PluginDescription(id = "authserver", name = "AuthServer", version = version, main = ""), logger, directory)
    }

    private val playersByName = ConcurrentHashMap<String, LimboPlayer>()
    private val network = NetworkServer(this)
    private var api: ProxyApi? = null
    private val console = Console(this)
    private val stopped = AtomicBoolean(false)

    override val players: Collection<Player> get() = playersByName.values

    override fun player(name: String): Player? = playersByName[name.lowercase()]

    override fun player(uniqueId: UUID): Player? = playersByName.values.firstOrNull { it.uniqueId == uniqueId }

    /** Registers a player that finished logging in; `false` if the name is already online. */
    fun addPlayer(player: LimboPlayer): Boolean = playersByName.putIfAbsent(player.username.lowercase(), player) == null

    fun removePlayer(player: LimboPlayer) {
        if (playersByName.remove(player.username.lowercase(), player)) player.dispose()
    }

    fun commandTree(player: Player): List<CommandNode> = commands.tree(player)

    fun localeOf(source: CommandSource): Locale = (source as? Player)?.locale ?: Locale.forLanguageTag(config.messages.defaultLanguage)

    fun start() {
        CoreCommands(this).register()
        plugins.load(this)
        plugins.enableAll()
        network.bind(config.network.host, config.network.port)
        if (config.api.enabled) api = ProxyApi(this).also { it.start(config.api.host, config.api.port) }
        if (proxied && !config.api.enabled) logger.warn("The HTTP API is disabled; the Velocity plugin cannot decide online/offline mode")
        events.post(ServerStartedEvent())
        console.start()
        logger.info("AuthServer {} is listening on {}:{} (forwarding: {})", version, config.network.host, config.network.port,
            config.proxy.forwarding)
    }

    /** Idempotent: the console `stop` command and the JVM shutdown hook may both get here. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        logger.info("Stopping...")
        events.post(ServerStoppingEvent())
        playersByName.values.forEach { it.kick(messages.render(it.locale, "kick-shutdown")) }
        plugins.disableAll()
        api?.stop()
        network.close()
        scope.cancel()
        database.close()
    }
}
