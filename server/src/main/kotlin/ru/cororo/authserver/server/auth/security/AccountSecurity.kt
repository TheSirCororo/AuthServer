package ru.cororo.authserver.server.auth.security

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.slf4j.LoggerFactory
import ru.cororo.authserver.server.config.EmailConfig
import ru.cororo.authserver.server.config.Messages
import ru.cororo.authserver.server.config.TwoFactorConfig
import ru.cororo.authserver.server.mail.MailSender
import ru.cororo.authserver.storage.Account
import ru.cororo.authserver.storage.AccountRepository
import ru.cororo.authserver.storage.EmailAddresses
import ru.cororo.authserver.storage.TwoFactorMethod
import ru.cororo.authserver.storage.password.PasswordHasher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Who runs `/email` or `/2fa`: a player in the limbo, or an authenticated player elsewhere through the proxy. */
interface SecurityActor {
    val username: String
    val locale: Locale
    fun reply(message: Component)
}

/**
 * Email addresses and two-factor authentication of accounts: the `/email` and `/2fa` commands, and the codes that
 * logins and password recovery check. Shared by the limbo and the HTTP API, so pending setups and codes are the
 * same wherever the player types the command.
 *
 * Changing the address needs the password; turning two-factor authentication off needs a current code, so a stolen
 * session alone cannot weaken an account.
 */
class AccountSecurity(
    private val email: EmailConfig,
    private val twoFactor: TwoFactorConfig,
    private val accounts: AccountRepository,
    private val hasher: PasswordHasher,
    private val hashing: CoroutineDispatcher,
    private val messages: Messages,
    private val mail: MailSender,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(AccountSecurity::class.java)
    private val codes = EmailCodes(Duration.ofMinutes(email.codeMinutes.toLong()), Duration.ofSeconds(email.resendSeconds.toLong()), email.maxCodeAttempts, clock)

    /** An authenticator secret waiting for its first code. */
    private data class PendingTotp(val secret: String, val expiresAt: Instant)

    private val pendingTotp = ConcurrentHashMap<String, PendingTotp>()
    /** Last accepted time step per account, so an authenticator code works once. */
    private val usedSteps = ConcurrentHashMap<String, Long>()

    /** Wrong passwords and authenticator codes in these commands, so a hijacked session cannot guess them. */
    private data class Failures(val count: Int, val blockedUntil: Instant?)

    private val failures = ConcurrentHashMap<String, Failures>()

    val emailEnabled: Boolean get() = email.enabled

    // ------------------------------------------------------------------------------------------------ /email

    suspend fun emailCommand(actor: SecurityActor, arguments: List<String>) {
        if (!email.enabled) return actor.say("email-unavailable")
        if (blocked(actor)) return
        val account = find(actor.username) ?: return actor.say("not-registered")
        when (arguments.firstOrNull()?.lowercase()) {
            null -> actor.say(if (account.email == null) "email-status-none" else "email-status", "address" to (account.email ?: ""))
            "set" -> {
                val address = arguments.getOrNull(1)?.let(EmailAddresses::normalise) ?: return actor.say("email-usage")
                if (account.twoFactor == TwoFactorMethod.EMAIL) return actor.say("email-used-by-2fa")
                if (!passwordMatches(account, arguments.getOrNull(2))) return failed(actor, "security-wrong-password")
                send(actor, CodePurpose.CONFIRM_EMAIL, address, "email-code-sent")
            }
            "confirm" -> {
                val code = code(arguments) ?: return actor.say("email-usage")
                val address = checkCode(actor, CodePurpose.CONFIRM_EMAIL, code) ?: return
                withIo { accounts.setEmail(account.id, address) }
                logger.info("{} linked the email {}", account.username, EmailAddresses.mask(address))
                actor.say("email-linked", "address" to address)
            }
            "remove" -> {
                if (account.email == null) return actor.say("email-status-none")
                if (account.twoFactor == TwoFactorMethod.EMAIL) return actor.say("email-used-by-2fa")
                if (!passwordMatches(account, arguments.getOrNull(1))) return failed(actor, "security-wrong-password")
                withIo { accounts.setEmail(account.id, null) }
                actor.say("email-removed")
            }
            else -> actor.say("email-usage")
        }
    }

    // ------------------------------------------------------------------------------------------------ /2fa

    suspend fun twoFactorCommand(actor: SecurityActor, arguments: List<String>) {
        if (blocked(actor)) return
        val account = find(actor.username) ?: return actor.say("not-registered")
        when (arguments.firstOrNull()?.lowercase()) {
            null -> if (account.twoFactor == TwoFactorMethod.NONE) {
                actor.say(if (twoFactor.enabled) "2fa-status-off" else "2fa-unavailable")
            } else {
                actor.say("2fa-status-on", "method" to plain(actor, "2fa-method-${account.twoFactor.name.lowercase()}"))
            }
            "totp", "app" -> {
                if (!canEnable(actor, account)) return
                val secret = Totp.generateSecret()
                pendingTotp[key(account.username)] = PendingTotp(secret, clock.instant() + SETUP_TIME)
                actor.say("2fa-totp-setup", "name" to account.username, "issuer" to twoFactor.issuer)
                actor.reply(Component.text(secret.chunked(4).joinToString(" "), NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.copyToClipboard(secret))
                    .hoverEvent(HoverEvent.showText(messages.render(actor.locale, "click-to-copy"))))
                actor.say("2fa-totp-confirm")
            }
            "email" -> {
                if (!canEnable(actor, account)) return
                if (!email.enabled) return actor.say("email-unavailable")
                val address = account.email ?: return actor.say("2fa-email-needs-address")
                pendingTotp.remove(key(account.username))
                send(actor, CodePurpose.ENABLE_TWO_FACTOR, address, "2fa-code-sent-enable")
            }
            "confirm" -> {
                val code = code(arguments) ?: return actor.say("2fa-usage")
                val pending = pendingTotp[key(account.username)]?.takeIf { it.expiresAt > clock.instant() }
                if (pending != null) {
                    if (!acceptTotp(account.username, pending.secret, code)) return failed(actor, "code-wrong-totp")
                    pendingTotp.remove(key(account.username))
                    withIo { accounts.setTwoFactor(account.id, TwoFactorMethod.TOTP, pending.secret) }
                } else {
                    checkCode(actor, CodePurpose.ENABLE_TWO_FACTOR, code) ?: return
                    withIo { accounts.setTwoFactor(account.id, TwoFactorMethod.EMAIL, null) }
                }
                logger.info("{} turned on two-factor authentication", account.username)
                actor.say("2fa-enabled")
            }
            "off", "disable" -> {
                val code = code(arguments)
                when (account.twoFactor) {
                    TwoFactorMethod.NONE -> return actor.say("2fa-status-off")
                    TwoFactorMethod.TOTP -> {
                        if (code == null) return actor.say("2fa-off-totp")
                        if (!acceptTotp(account.username, requireNotNull(account.totpSecret), code)) return failed(actor, "code-wrong-totp")
                    }
                    TwoFactorMethod.EMAIL -> {
                        val address = account.email
                        if (code == null) {
                            if (address == null || !email.enabled) return actor.say("email-unavailable")
                            return send(actor, CodePurpose.DISABLE_TWO_FACTOR, address, "2fa-code-sent-disable")
                        }
                        checkCode(actor, CodePurpose.DISABLE_TWO_FACTOR, code) ?: return
                    }
                }
                withIo { accounts.setTwoFactor(account.id, TwoFactorMethod.NONE, null) }
                logger.info("{} turned off two-factor authentication", account.username)
                actor.say("2fa-disabled")
            }
            else -> actor.say("2fa-usage")
        }
    }

    private fun canEnable(actor: SecurityActor, account: Account): Boolean = when {
        !twoFactor.enabled -> false.also { actor.say("2fa-unavailable") }
        account.twoFactor != TwoFactorMethod.NONE -> false.also { actor.say("2fa-already") }
        else -> true
    }

    // ------------------------------------------------------------------------------------------------ login and recovery

    /** Sends [purpose]'s code to [address]; tells [actor] where it went, or why it was not sent. */
    suspend fun send(actor: SecurityActor, purpose: CodePurpose, address: String, sentKey: String) {
        when (val issued = codes.issue(actor.username, purpose, address)) {
            is EmailCodes.Issue.Cooldown -> actor.say("code-cooldown", "seconds" to issued.seconds)
            is EmailCodes.Issue.Issued -> {
                val subject = plain(actor, "email-subject-${purpose.messageKey}")
                val text = plain(actor, "email-text", "name" to actor.username, "code" to issued.code,
                    "action" to plain(actor, "email-action-${purpose.messageKey}"), "minutes" to email.codeMinutes)
                val sent = withIo { runCatching { mail.send(address, subject, text) } }
                sent.onFailure {
                    logger.warn("Could not send a {} code to {} for {}: {}", purpose, EmailAddresses.mask(address), actor.username, it.toString())
                    codes.discard(actor.username, purpose)
                    return actor.say("email-send-failed")
                }
                actor.say(sentKey, "address" to EmailAddresses.mask(address))
            }
        }
    }

    /** The address the code was sent to when [code] is right; otherwise tells [actor] what is wrong. */
    fun checkCode(actor: SecurityActor, purpose: CodePurpose, code: String): String? = when (val check = codes.check(actor.username, purpose, code)) {
        is EmailCodes.Check.Valid -> check.address
        is EmailCodes.Check.Wrong -> null.also {
            if (check.attemptsLeft > 0) actor.say("code-wrong", "attempts" to check.attemptsLeft) else actor.say("code-missing")
        }
        EmailCodes.Check.Missing -> null.also { actor.say("code-missing") }
    }

    /** Checks an authenticator code; each time step is accepted once per account. */
    fun acceptTotp(username: String, secret: String, code: String): Boolean {
        val step = Totp.matchingStep(secret, code, clock.instant(), twoFactor.totpWindow) ?: return false
        var fresh = false
        usedSteps.compute(key(username)) { _, last -> if (last == null || step > last) step.also { fresh = true } else last }
        return fresh
    }

    // ------------------------------------------------------------------------------------------------ administration

    suspend fun disableTwoFactor(username: String): Boolean {
        val account = find(username) ?: return false
        withIo { accounts.setTwoFactor(account.id, TwoFactorMethod.NONE, null) }
        return true
    }

    /** Sets (or with `null` removes) an address without confirmation; `false` for unknown accounts or bad addresses. */
    suspend fun setEmail(username: String, address: String?): Boolean {
        val account = find(username) ?: return false
        val normalised = address?.let { EmailAddresses.normalise(it) ?: return false }
        if (normalised == null && account.twoFactor == TwoFactorMethod.EMAIL) withIo { accounts.setTwoFactor(account.id, TwoFactorMethod.NONE, null) }
        withIo { accounts.setEmail(account.id, normalised) }
        return true
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The code after the sub-command; authenticator apps show it as "123 456", so the parts are joined. */
    private fun code(arguments: List<String>): String? = arguments.drop(1).joinToString("").ifEmpty { null }

    /**
     * Tells [actor] to wait while the account is locked after too many wrong passwords or codes. The lock is per
     * account, so reconnecting does not reset it.
     */
    fun blocked(actor: SecurityActor): Boolean {
        val until = failures[key(actor.username)]?.blockedUntil ?: return false
        val seconds = Duration.between(clock.instant(), until).toSeconds()
        if (seconds < 0) return false
        actor.say("security-locked", "seconds" to seconds + 1)
        return true
    }

    fun failed(actor: SecurityActor, key: String) {
        failures.compute(key(actor.username)) { _, old ->
            val count = (old?.takeIf { it.blockedUntil == null }?.count ?: 0) + 1
            if (count >= MAX_FAILURES) Failures(0, clock.instant() + LOCK_TIME) else Failures(count, null)
        }
        actor.say(key)
    }

    /** Accounts without a password (licensed ones) prove themselves through Mojang and need none here. */
    private suspend fun passwordMatches(account: Account, password: String?): Boolean {
        val hash = account.passwordHash ?: return true
        if (password == null) return false
        return withContext(hashing) { hasher.verify(password, hash) }.valid
    }

    private suspend fun find(username: String) = withIo { accounts.find(username) }

    private fun SecurityActor.say(key: String, vararg placeholders: Pair<String, Any>) = reply(messages.render(locale, key, *placeholders))

    private fun plain(actor: SecurityActor, key: String, vararg placeholders: Pair<String, Any>) =
        PlainTextComponentSerializer.plainText().serialize(messages.render(actor.locale, key, *placeholders))

    private val CodePurpose.messageKey get() = name.lowercase().replace('_', '-')

    private fun key(username: String) = username.lowercase(Locale.ROOT)

    private suspend fun <T> withIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        val SETUP_TIME: Duration = Duration.ofMinutes(10)
        const val MAX_FAILURES = 5
        val LOCK_TIME: Duration = Duration.ofMinutes(5)
    }
}
