package ru.cororo.authserver.server.auth

import ru.cororo.authserver.storage.TwoFactorMethod
import ru.cororo.authserver.storage.EmailAddresses
import ru.cororo.authserver.server.auth.security.CodePurpose
import ru.cororo.authserver.server.auth.security.AccountSecurity
import kotlinx.coroutines.CoroutineDispatcher
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
    private val licensedRequests: LicensedLoginRequests,
    private val security: AccountSecurity,
    /** Limits how many passwords are hashed at once (Argon2 needs a lot of memory). */
    private val hashing: CoroutineDispatcher,
    private val commandsChanged: (LimboPlayer) -> Unit,
    private val scope: CoroutineScope,
) : AuthService {
    private val logger = LoggerFactory.getLogger(AuthManager::class.java)
    private val usernamePattern = Regex(config.usernamePattern)

    fun isValidName(username: String) = usernamePattern.matches(username)

    // ------------------------------------------------------------------------------------------------ joining

    /** Decides what a player who just entered the limbo has to do. */
    suspend fun onJoin(player: LimboPlayer) {
        val account = io { accounts.find(player.username) }
        if (account != null && account.username != player.username) {
            return fail(player, FailureReason.INVALID_NAME, "kick-wrong-case", "name" to account.username)
        }
        var licensedFailed = false
        when (player.mode) {
            AuthMode.ONLINE -> when {
                account == null -> {
                    licensedRequests.clear(player.username)
                    try {
                        io { accounts.create(player.username, null, ip(player), premium = true, premiumUuid = player.uniqueId) }
                    } catch (_: AccountExistsException) {
                        return fail(player, FailureReason.PREMIUM_VERIFICATION_FAILED, "kick-premium-mismatch")
                    }
                    return authenticate(player, AuthMethod.PREMIUM)
                }
                account.premium -> {
                    if (account.premiumUuid != null && account.premiumUuid != player.uniqueId) {
                        return fail(player, FailureReason.PREMIUM_VERIFICATION_FAILED, "kick-premium-mismatch")
                    }
                    if (account.premiumUuid == null) io { accounts.setPremium(account.id, true, player.uniqueId) }
                    return passFirstFactor(player, AuthMethod.PREMIUM, account)
                }
                // A licensed player whose name was registered with a password must know that password.
            }
            AuthMode.OFFLINE -> when {
                account != null && account.passwordHash == null && !config.licensedLogin ->
                    return fail(player, FailureReason.PREMIUM_VERIFICATION_FAILED, "kick-licensed-disabled")
                account?.premium == true && config.licensedLogin ->
                    return fail(player, FailureReason.PREMIUM_VERIFICATION_FAILED, "kick-premium-account")
                account != null && canResume(player, account) -> return authenticate(player, AuthMethod.SESSION)
                // Came back without Mojang after choosing a licensed login: that did not work, choose again.
                account == null -> licensedFailed = licensedRequests.failed(player.username)
            }
        }
        if (licensedFailed) player.say("licensed-login-failed")
        loginInterface.onPrompt(player, registered = account != null)
        startPrompts(player, if (account != null) "login" else "register")
    }

    /**
     * The player chose a licensed login (`MANUAL` policy): the next connection with this name goes through Mojang.
     * Clients from 1.20.5 are transferred back right away (by the proxy plugin behind Velocity); older ones reconnect.
     */
    suspend fun requestLicensedLogin(player: LimboPlayer) {
        if (player.isAuthenticated || !player.canChooseLicensed || io { accounts.find(player.username) } != null) return
        licensedRequests.request(player.username)
        logger.info("{} chose a licensed login", player.username)
        val message = messages.render(player.locale, "kick-licensed-chosen")
        val handedOver = when {
            bridge != null -> {
                player.sendPluginMessage(BridgeCodec.CHANNEL, bridge.encode(BridgeMessage.LicensedLogin(
                    player.uniqueId, player.username, System.currentTimeMillis(), PlainTextComponentSerializer.plainText().serialize(message),
                )))
                true
            }
            else -> player.virtualHost?.let { player.transfer(it.hostString, it.port) } ?: false
        }
        // After a transfer the client leaves by itself; the kick covers old clients and proxies that did not act.
        if (handedOver) delay(RECONNECT_GRACE_MILLIS)
        player.kick(message)
    }

    private fun canResume(player: LimboPlayer, account: Account): Boolean {
        if (config.sessionMinutes <= 0) return false
        val last = account.lastLoginAt ?: return false
        return account.lastLoginIp == ip(player) && Duration.between(last, Instant.now()) < Duration.ofMinutes(config.sessionMinutes.toLong())
    }

    /**
     * Shows what to type ([step] names the `title-`/`subtitle-`/`prompt-` messages) and starts the reminders and the
     * login timeout; a later step replaces the earlier one's jobs, so every step gets the full time.
     */
    private fun startPrompts(player: LimboPlayer, step: String, vararg placeholders: Pair<String, Any>) {
        player.authJobs.forEach { it.cancel() }
        player.authJobs.clear()
        player.prompt = "prompt-$step" to arrayOf(*placeholders)
        if (config.showTitles) {
            player.showTitle(Title.title(
                messages.render(player.locale, "title-$step"), messages.render(player.locale, "subtitle-$step", *placeholders),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(config.loginTimeoutSeconds.toLong()), Duration.ofMillis(250)),
            ))
        }
        remind(player)
        player.authJobs += player.launch {
            while (isActive) {
                delay(config.reminderSeconds.coerceAtLeast(1) * 1000L)
                remind(player)
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

    private fun remind(player: LimboPlayer) {
        val (key, placeholders) = player.prompt
        player.say(key, *placeholders)
    }

    // ------------------------------------------------------------------------------------------------ second factor

    /**
     * The password, Mojang or a recovery code proved the first factor. Accounts with two-factor authentication then
     * need a code (`/code`); a resumed session does not, as it continues a login that had one.
     */
    private suspend fun passFirstFactor(player: LimboPlayer, method: AuthMethod, account: Account) {
        when (account.twoFactor) {
            TwoFactorMethod.NONE -> authenticate(player, method)
            TwoFactorMethod.TOTP -> {
                player.secondFactor = LimboPlayer.SecondFactor(method, TwoFactorMethod.TOTP, null)
                loginInterface.onPrompt(player, registered = true)
                startPrompts(player, "code-totp")
            }
            TwoFactorMethod.EMAIL -> {
                // Without mail the code cannot arrive; an administrator can turn two-factor authentication off.
                val address = account.email?.takeIf { security.emailEnabled }
                    ?: return fail(player, FailureReason.OTHER, "kick-2fa-unavailable")
                player.secondFactor = LimboPlayer.SecondFactor(method, TwoFactorMethod.EMAIL, address)
                loginInterface.onPrompt(player, registered = true)
                security.send(player, CodePurpose.LOGIN, address, "code-sent-login")
                startPrompts(player, "code-email", "address" to EmailAddresses.mask(address))
            }
        }
    }

    /** `/code <code>`; for email codes a bare `/code` sends a new one. */
    suspend fun submitCode(player: LimboPlayer, code: String?) {
        val pending = player.secondFactor ?: return player.say(if (player.isAuthenticated) "code-not-needed" else player.prompt.first)
            .also { logger.info("{} sent /code without a pending code", player.username) }
        val accepted = when (pending.factor) {
            TwoFactorMethod.TOTP -> {
                val secret = io { accounts.find(player.username) }?.totpSecret ?: return
                if (code == null) return remind(player).also { logger.info("{} sent /code without a code", player.username) }
                if (security.blocked(player)) return
                security.acceptTotp(player.username, secret, code).also { if (!it) security.failed(player, "code-wrong-totp") }
            }
            TwoFactorMethod.EMAIL -> {
                val address = pending.address ?: return
                if (code == null) return security.send(player, CodePurpose.LOGIN, address, "code-sent-login")
                security.checkCode(player, CodePurpose.LOGIN, code) != null
            }
            TwoFactorMethod.NONE -> true
        }
        logger.info("{} entered a {} code: {}", player.username, pending.factor, if (accepted) "accepted" else "rejected")
        if (!accepted) {
            player.failedAttempts++
            if (player.failedAttempts >= config.maxLoginAttempts) fail(player, FailureReason.WRONG_PASSWORD, "kick-too-many-attempts")
            return
        }
        player.secondFactor = null
        authenticate(player, pending.method)
    }

    // ------------------------------------------------------------------------------------------------ recovery

    /** `/recover` mails a code; `/recover <code> <password> <password>` sets a new password and logs in. */
    suspend fun recover(player: LimboPlayer, arguments: List<String>) {
        if (player.isAuthenticated || player.secondFactor != null) return
        if (!security.emailEnabled) return player.say("recover-unavailable")
        val account = io { accounts.find(player.username) } ?: return player.say("not-registered")
        if (account.passwordHash == null) return player.say("premium-already")
        val address = account.email ?: return player.say("recover-no-email")
        if (arguments.isEmpty()) return security.send(player, CodePurpose.RECOVER, address, "recover-code-sent")
        if (arguments.size != 3) return player.say("recover-usage")
        val (code, password, confirmation) = arguments
        if (password != confirmation) return player.say("password-mismatch")
        // Checked before the code, so a rejected password does not use the code up.
        passwordIssue(player.username, password)?.let { return player.sendMessage(message(player, it)) }
        if (security.checkCode(player, CodePurpose.RECOVER, code) == null) {
            player.failedAttempts++
            if (player.failedAttempts >= config.maxLoginAttempts) fail(player, FailureReason.WRONG_PASSWORD, "kick-too-many-attempts")
            return
        }
        val hash = withContext(hashing) { hasher.hash(password) }
        io { accounts.updatePassword(account.id, hash) }
        logger.info("{} set a new password with an email code", player.username)
        player.say("recover-success")
        // The emailed code already proved the address, which is the second factor of email-protected accounts.
        passFirstFactor(player, AuthMethod.RECOVERY, if (account.twoFactor == TwoFactorMethod.EMAIL) account.copy(twoFactor = TwoFactorMethod.NONE) else account)
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
        if (player.secondFactor != null) return remind(player)
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
        passFirstFactor(player, AuthMethod.LOGIN, account)
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

    /**
     * `/premium` then `/premium confirm`: the account logs in through Mojang from now on. A new player under the
     * `MANUAL` policy gets the licensed login of the menu instead.
     */
    suspend fun enablePremium(player: LimboPlayer, confirmed: Boolean, commandName: String) {
        val account = io { accounts.find(player.username) }
        if (account == null) {
            if (!player.canChooseLicensed) return player.say("not-registered")
            if (!confirmed || !player.premiumConfirmationPending) {
                player.premiumConfirmationPending = true
                return player.say("premium-choose-confirm", "command" to commandName)
            }
            return requestLicensedLogin(player)
        }
        if (!player.isAuthenticated) return player.say("login-first")
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
            AuthMethod.RECOVERY -> "success-login"
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

    private companion object {
        /** Time the client (or the proxy plugin) gets to act on a transfer before the auth server kicks the player. */
        const val RECONNECT_GRACE_MILLIS = 3000L
    }

    // ------------------------------------------------------------------------------------------------ plugin API

    override fun account(username: String): CompletableFuture<AccountInfo?> = scope.future(Dispatchers.IO) {
        accounts.find(username)?.let {
            AccountInfo(it.username, it.premium, it.premiumUuid, it.passwordHash != null, it.registeredAt, it.lastLoginAt, it.lastLoginIp,
                it.email, it.twoFactor != TwoFactorMethod.NONE)
        }
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
