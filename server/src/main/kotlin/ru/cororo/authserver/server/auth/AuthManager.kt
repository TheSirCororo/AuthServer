package ru.cororo.authserver.server.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.slf4j.LoggerFactory
import ru.cororo.authserver.api.auth.AccountInfo
import ru.cororo.authserver.api.auth.AuthResult
import ru.cororo.authserver.api.auth.AuthService
import ru.cororo.authserver.api.auth.AuthState
import ru.cororo.authserver.api.event.PlayerAuthFailedEvent
import ru.cororo.authserver.api.event.PlayerAuthenticatedEvent
import ru.cororo.authserver.api.event.PlayerLoginAttemptEvent
import ru.cororo.authserver.api.event.PlayerRegisterEvent
import ru.cororo.authserver.api.event.EventManager
import ru.cororo.authserver.api.player.Player
import ru.cororo.authserver.bridge.AccountApi.PasswordChangeResult
import ru.cororo.authserver.bridge.AuthMethod
import ru.cororo.authserver.bridge.AuthMode
import ru.cororo.authserver.bridge.BridgeCodec
import ru.cororo.authserver.bridge.BridgeMessage
import ru.cororo.authserver.bridge.FailureReason
import ru.cororo.authserver.server.config.AuthenticationConfig
import ru.cororo.authserver.server.config.Messages
import ru.cororo.authserver.server.config.TransferConfig
import ru.cororo.authserver.server.player.LimboPlayer
import ru.cororo.authserver.storage.Account
import ru.cororo.authserver.storage.AccountExistsException
import ru.cororo.authserver.storage.AccountRepository
import ru.cororo.authserver.storage.password.PasswordHasher
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture

/**
 * Authentication rules. Player-facing methods run inside the player's sequential scope, so the steps of one
 * player never interleave; blocking work (database, hashing, plugin events) happens on I/O threads.
 */
class AuthManager(
    private val config: AuthenticationConfig,
    private val accounts: AccountRepository,
    private val hasher: PasswordHasher,
    private val messages: Messages,
    private val events: EventManager,
    /** Signs results for the proxy plugin; `null` when players connect directly. */
    private val bridge: BridgeCodec?,
    private val transfer: TransferConfig,
    private val loginInterface: LoginInterface,
    private val commandsChanged: (LimboPlayer) -> Unit,
    private val scope: CoroutineScope,
) : AuthService {
    private val logger = LoggerFactory.getLogger(AuthManager::class.java)
    private val hashing = Dispatchers.Default.limitedParallelism(config.hashing.threads.coerceAtLeast(1))
    private val usernamePattern = Regex(config.usernamePattern)

    fun isValidName(username: String) = usernamePattern.matches(username)

    // ------------------------------------------------------------------------------------------------ joining

    /** Decides what a player who just entered the limbo has to do. */
    suspend fun onJoin(player: LimboPlayer) {
        val account = io { accounts.find(player.username) }
        if (account != null && account.username != player.username) {
            return fail(player, FailureReason.INVALID_NAME, "kick-wrong-case", "name" to account.username)
        }
        when (player.mode) {
            AuthMode.ONLINE -> when {
                account == null -> {
                    io { accounts.create(player.username, null, ip(player), premium = true, premiumUuid = player.uniqueId) }
                    return authenticate(player, AuthMethod.PREMIUM)
                }
                account.premium -> {
                    if (account.premiumUuid != null && account.premiumUuid != player.uniqueId) {
                        return fail(player, FailureReason.PREMIUM_VERIFICATION_FAILED, "kick-premium-mismatch")
                    }
                    if (account.premiumUuid == null) io { accounts.setPremium(account.id, true, player.uniqueId) }
                    return authenticate(player, AuthMethod.PREMIUM)
                }
                // A licensed player whose name was registered with a password must know that password.
            }
            AuthMode.OFFLINE -> when {
                account != null && account.passwordHash == null && !config.licensedLogin ->
                    return fail(player, FailureReason.PREMIUM_VERIFICATION_FAILED, "kick-licensed-disabled")
                account?.premium == true && config.licensedLogin ->
                    return fail(player, FailureReason.PREMIUM_VERIFICATION_FAILED, "kick-premium-account")
                account != null && canResume(player, account) -> return authenticate(player, AuthMethod.SESSION)
            }
        }
        startPrompts(player, registered = account != null)
    }

    private fun canResume(player: LimboPlayer, account: Account): Boolean {
        if (config.sessionMinutes <= 0) return false
        val last = account.lastLoginAt ?: return false
        return account.lastLoginIp == ip(player) && Duration.between(last, Instant.now()) < Duration.ofMinutes(config.sessionMinutes.toLong())
    }

    private fun startPrompts(player: LimboPlayer, registered: Boolean) {
        loginInterface.onPrompt(player, registered)
        val prompt = if (registered) "prompt-login" else "prompt-register"
        val kind = if (registered) "login" else "register"
        if (config.showTitles) {
            player.showTitle(Title.title(
                messages.render(player.locale, "title-$kind"), messages.render(player.locale, "subtitle-$kind"),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(config.loginTimeoutSeconds.toLong()), Duration.ofMillis(250)),
            ))
        }
        player.sendMessage(messages.render(player.locale, prompt))
        player.authJobs += player.launch {
            while (isActive) {
                delay(config.reminderSeconds.coerceAtLeast(1) * 1000L)
                player.sendMessage(messages.render(player.locale, prompt))
            }
        }
        val timeout = config.loginTimeoutSeconds
        val bar = if (config.showBossBar) {
            BossBar.bossBar(messages.render(player.locale, "boss-bar-timer", "seconds" to timeout), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
                .also(player::showBossBar)
        } else null
        player.authJobs += player.launch {
            for (left in timeout downTo 1) {
                bar?.name(messages.render(player.locale, "boss-bar-timer", "seconds" to left))
                bar?.progress(left.toFloat() / timeout)
                delay(1000)
            }
            fail(player, FailureReason.TIMEOUT, "kick-timeout")
        }
        player.authJobs += player.launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                bar?.let(player::hideBossBar)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ commands

    suspend fun register(player: LimboPlayer, password: String, confirmation: String) {
        if (player.isAuthenticated) return
        if (io { accounts.find(player.username) } != null) return player.say("already-registered")
        if (password != confirmation) return player.say("password-mismatch")
        passwordIssue(player.username, password)?.let { return player.sendMessage(message(player, it)) }
        if (config.maxAccountsPerIp > 0 && io { accounts.countByRegistrationIp(ip(player)) } >= config.maxAccountsPerIp) {
            return player.say("ip-limit")
        }
        val event = io { events.post(PlayerRegisterEvent(player)) }
        if (event.isCancelled) return player.sendMessage(event.reason ?: messages.render(player.locale, "registration-denied"))
        val hash = withContext(hashing) { hasher.hash(password) }
        try {
            io { accounts.create(player.username, hash, ip(player)) }
        } catch (_: AccountExistsException) {
            return player.say("already-registered")
        }
        authenticate(player, AuthMethod.REGISTER)
    }

    suspend fun login(player: LimboPlayer, password: String) {
        if (player.isAuthenticated) return
        val account = io { accounts.find(player.username) } ?: return player.say("not-registered")
        val hash = account.passwordHash ?: return player.say("not-registered")
        val verification = withContext(hashing) { hasher.verify(password, hash) }
        if (!verification.valid) {
            player.failedAttempts++
            val remaining = (config.maxLoginAttempts - player.failedAttempts).coerceAtLeast(0)
            io { events.post(PlayerLoginAttemptEvent(player, success = false, remainingAttempts = remaining)) }
            if (remaining == 0) return fail(player, FailureReason.WRONG_PASSWORD, "kick-too-many-attempts")
            return player.say("wrong-password", "attempts" to remaining)
        }
        io { events.post(PlayerLoginAttemptEvent(player, success = true, remainingAttempts = config.maxLoginAttempts - player.failedAttempts)) }
        if (verification.needsRehash) {
            val upgraded = withContext(hashing) { hasher.hash(password) }
            io { accounts.updatePassword(account.id, upgraded) }
        }
        authenticate(player, AuthMethod.LOGIN)
    }

    suspend fun changePassword(player: LimboPlayer, oldPassword: String, newPassword: String) =
        player.sendMessage(message(player, changePassword(player.username, oldPassword, newPassword)))

    /** Shared by `/changepassword` in the limbo and the proxy's `/changepassword` through the HTTP API. */
    suspend fun changePassword(username: String, oldPassword: String, newPassword: String): PasswordChangeResult {
        val account = io { accounts.find(username) } ?: return PasswordChangeResult.NOT_REGISTERED
        val hash = account.passwordHash ?: return PasswordChangeResult.NO_PASSWORD
        if (!withContext(hashing) { hasher.verify(oldPassword, hash) }.valid) return PasswordChangeResult.WRONG_PASSWORD
        passwordIssue(account.username, newPassword)?.let { return it }
        val newHash = withContext(hashing) { hasher.hash(newPassword) }
        io { accounts.updatePassword(account.id, newHash) }
        return PasswordChangeResult.CHANGED
    }

    /** Ends the account's session so the next join asks for the password again (proxy `/logout`). */
    suspend fun logout(username: String) {
        io { accounts.find(username)?.let { accounts.clearSession(it.id) } }
    }

    val passwordLimits: IntRange get() = config.minPasswordLength..config.maxPasswordLength

    suspend fun unregister(player: LimboPlayer, password: String) {
        val account = io { accounts.find(player.username) } ?: return player.say("not-registered")
        val hash = account.passwordHash ?: return player.say("premium-already")
        if (!withContext(hashing) { hasher.verify(password, hash) }.valid) {
            return player.say("wrong-password", "attempts" to (config.maxLoginAttempts - player.failedAttempts))
        }
        io { accounts.delete(account.id) }
        player.kick(messages.render(player.locale, "kick-unregistered"))
    }

    /** `/premium` then `/premium confirm`: the account logs in through Mojang from now on. */
    suspend fun enablePremium(player: LimboPlayer, confirmed: Boolean, commandName: String) {
        val account = io { accounts.find(player.username) } ?: return player.say("not-registered")
        if (account.premium) return player.say("premium-already")
        if (!confirmed || !player.premiumConfirmationPending) {
            player.premiumConfirmationPending = true
            return player.say("premium-confirm", "command" to commandName)
        }
        // A Mojang-verified session proves ownership right away; otherwise the UUID is bound on the next licensed login.
        io { accounts.setPremium(account.id, true, if (player.mode == AuthMode.ONLINE) player.uniqueId else null) }
        player.say("premium-enabled")
    }

    // ------------------------------------------------------------------------------------------------ outcomes

    suspend fun authenticate(player: LimboPlayer, method: AuthMethod) {
        if (player.authState == AuthState.AUTHENTICATED || !player.isOnline) return
        player.authState = AuthState.AUTHENTICATED
        player.authJobs.forEach { it.cancel() }
        player.authJobs.clear()
        loginInterface.onAuthenticated(player)
        io { accounts.find(player.username) }?.let { account -> io { accounts.recordLogin(account.id, ip(player)) } }
        val event = io { events.post(PlayerAuthenticatedEvent(player, player.mode, method)) }
        commandsChanged(player)
        if (config.showTitles) {
            player.showTitle(Title.title(messages.render(player.locale, "title-success"), messages.render(player.locale, "subtitle-success"),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))))
        }
        player.say(when (method) {
            AuthMethod.REGISTER -> "success-register"
            AuthMethod.SESSION -> "success-session"
            AuthMethod.PREMIUM -> "success-premium"
            else -> "success-login"
        })
        logger.info("{} authenticated ({}, {})", player.username, player.mode, method)
        when {
            bridge != null -> {
                player.say("routing")
                player.sendPluginMessage(BridgeCodec.CHANNEL, bridge.encode(BridgeMessage.Authenticated(
                    player.uniqueId, player.username, System.currentTimeMillis(), player.mode, method,
                    Optional.ofNullable(event.targetServer),
                )))
            }
            transfer.host.isNotBlank() && player.transfer(transfer.host, transfer.port) -> Unit
            else -> player.say("authenticated-limbo")
        }
    }

    /** Reports the failure and disconnects; the player's remaining jobs stop when the connection closes. */
    suspend fun fail(player: LimboPlayer, reason: FailureReason, key: String, vararg placeholders: Pair<String, Any>) {
        if (!player.isOnline) return
        io { events.post(PlayerAuthFailedEvent(player, reason)) }
        val message = messages.render(player.locale, key, *placeholders)
        bridge?.let { codec ->
            player.sendPluginMessage(BridgeCodec.CHANNEL, codec.encode(BridgeMessage.Failed(
                player.uniqueId, player.username, System.currentTimeMillis(), reason,
                PlainTextComponentSerializer.plainText().serialize(message),
            )))
        }
        player.kick(message)
    }

    private fun passwordIssue(username: String, password: String): PasswordChangeResult? = when {
        password.length < config.minPasswordLength -> PasswordChangeResult.TOO_SHORT
        password.length > config.maxPasswordLength -> PasswordChangeResult.TOO_LONG
        password.equals(username, ignoreCase = true) -> PasswordChangeResult.SAME_AS_NAME
        config.unsafePasswords.any { it.equals(password, ignoreCase = true) } -> PasswordChangeResult.UNSAFE
        else -> null
    }

    private fun message(player: Player, result: PasswordChangeResult) = when (result) {
        PasswordChangeResult.CHANGED -> messages.render(player.locale, "change-password-success")
        PasswordChangeResult.NOT_REGISTERED -> messages.render(player.locale, "not-registered")
        PasswordChangeResult.NO_PASSWORD -> messages.render(player.locale, "premium-already")
        PasswordChangeResult.WRONG_PASSWORD -> messages.render(player.locale, "wrong-password-change")
        PasswordChangeResult.TOO_SHORT -> messages.render(player.locale, "password-too-short", "min" to config.minPasswordLength)
        PasswordChangeResult.TOO_LONG -> messages.render(player.locale, "password-too-long", "max" to config.maxPasswordLength)
        PasswordChangeResult.SAME_AS_NAME -> messages.render(player.locale, "password-same-as-name")
        PasswordChangeResult.UNSAFE -> messages.render(player.locale, "password-unsafe")
    }

    private fun LimboPlayer.say(key: String, vararg placeholders: Pair<String, Any>) =
        sendMessage(messages.render(locale, key, *placeholders))

    private fun ip(player: Player): String = player.address.hostString

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ------------------------------------------------------------------------------------------------ plugin API

    override fun account(username: String): CompletableFuture<AccountInfo?> = scope.future(Dispatchers.IO) {
        accounts.find(username)?.let { AccountInfo(it.username, it.premium, it.premiumUuid, it.passwordHash != null, it.registeredAt, it.lastLoginAt, it.lastLoginIp) }
    }

    override fun register(username: String, password: String): CompletableFuture<AuthResult> = scope.future(Dispatchers.IO) {
        if (!isValidName(username)) return@future AuthResult.Failure("Invalid name")
        try {
            accounts.create(username, withContext(hashing) { hasher.hash(password) }, null)
            AuthResult.Success
        } catch (_: AccountExistsException) {
            AuthResult.Failure("Already registered")
        }
    }

    override fun changePassword(username: String, password: String): CompletableFuture<AuthResult> = scope.future(Dispatchers.IO) {
        val account = accounts.find(username) ?: return@future AuthResult.Failure("Not registered")
        accounts.updatePassword(account.id, withContext(hashing) { hasher.hash(password) })
        AuthResult.Success
    }

    override fun unregister(username: String): CompletableFuture<AuthResult> = scope.future(Dispatchers.IO) {
        val account = accounts.find(username) ?: return@future AuthResult.Failure("Not registered")
        accounts.delete(account.id)
        AuthResult.Success
    }

    override fun setPremium(username: String, premium: Boolean): CompletableFuture<AuthResult> = scope.future(Dispatchers.IO) {
        val account = accounts.find(username) ?: return@future AuthResult.Failure("Not registered")
        if (!premium && account.passwordHash == null) return@future AuthResult.Failure("The account has no password to fall back to")
        accounts.setPremium(account.id, premium, if (premium) account.premiumUuid else null)
        AuthResult.Success
    }

    override fun forceLogin(player: Player, method: AuthMethod) {
        val limboPlayer = player as LimboPlayer
        limboPlayer.launch { authenticate(limboPlayer, method) }
    }
}
