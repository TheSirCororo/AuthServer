package ru.cororo.authserver.server.auth.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** What an emailed code is for; a code only works for its own purpose. */
enum class CodePurpose {
    /** Confirms a new address before it is saved. */
    CONFIRM_EMAIL,
    /** Turns on email two-factor authentication. */
    ENABLE_TWO_FACTOR,
    /** Turns off email two-factor authentication. */
    DISABLE_TWO_FACTOR,
    /** Second step of a login. */
    LOGIN,
    /** Sets a new password without the old one. */
    RECOVER,
}

/**
 * Six-digit codes sent by email, kept in memory: each is valid for [ttl] and [maxAttempts] tries, and an account gets
 * at most one new code per [resend], whatever its purpose, so the commands cannot be used to flood a mailbox.
 */
class EmailCodes(
    private val ttl: Duration,
    private val resend: Duration,
    private val maxAttempts: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    sealed interface Issue {
        data class Issued(val code: String) : Issue
        data class Cooldown(val seconds: Long) : Issue
    }

    sealed interface Check {
        /** The code is right; it is used up. [address] is where it was sent. */
        data class Valid(val address: String) : Check
        data class Wrong(val attemptsLeft: Int) : Check
        /** No code, or it expired or ran out of attempts: a new one is needed. */
        data object Missing : Check
    }

    private data class Pending(val code: String, val address: String, val expiresAt: Instant, val attempts: Int)

    private val codes = ConcurrentHashMap<Pair<String, CodePurpose>, Pending>()
    private val lastSent = ConcurrentHashMap<String, Instant>()

    fun issue(username: String, purpose: CodePurpose, address: String): Issue {
        val now = clock.instant()
        codes.values.removeIf { it.expiresAt <= now }
        lastSent.values.removeIf { it + resend <= now }
        val user = key(username)
        var cooldown = 0L
        lastSent.compute(user) { _, last ->
            if (last != null && last + resend > now) {
                cooldown = Duration.between(now, last + resend).toSeconds() + 1
                last
            } else now
        }
        if (cooldown > 0) return Issue.Cooldown(cooldown)
        val code = (random.nextInt(1_000_000)).toString().padStart(6, '0')
        codes[user to purpose] = Pending(code, address, now + ttl, 0)
        return Issue.Issued(code)
    }

    fun check(username: String, purpose: CodePurpose, code: String): Check {
        val guess = code.filterNot(Char::isWhitespace)
        var result: Check = Check.Missing
        codes.computeIfPresent(key(username) to purpose) { _, pending ->
            when {
                pending.expiresAt <= clock.instant() -> null
                MessageDigest.isEqual(pending.code.toByteArray(), guess.toByteArray()) -> {
                    result = Check.Valid(pending.address)
                    null
                }
                pending.attempts + 1 >= maxAttempts -> {
                    result = Check.Wrong(0)
                    null
                }
                else -> {
                    result = Check.Wrong(maxAttempts - pending.attempts - 1)
                    pending.copy(attempts = pending.attempts + 1)
                }
            }
        }
        return result
    }

    fun discard(username: String, purpose: CodePurpose) {
        codes.remove(key(username) to purpose)
    }

    private fun key(username: String) = username.lowercase(Locale.ROOT)

    private companion object {
        val random = SecureRandom()
    }
}
