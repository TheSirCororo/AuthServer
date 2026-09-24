package ru.cororo.authserver.server.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Players of the `MANUAL` premium policy who chose a licensed login and have to reconnect for it. Nothing is stored
 * in the database until Mojang verified them, so a wrong choice only costs a reconnect:
 *
 * 1. [request] - the player picked the licensed login in the limbo and is reconnecting;
 * 2. [consume] - the next connection with that name is authenticated through Mojang (once);
 * 3. if that works the account is created and the request [clear]ed; if it does not, the player comes back offline
 *    and [failed] reports it, so the choice is offered again.
 *
 * Requests live in memory for [ttl]; the HTTP API that the proxy asks at pre-login runs in the same process.
 */
class LicensedLoginRequests(
    private val ttl: Duration = Duration.ofMinutes(3),
    private val clock: Clock = Clock.systemUTC(),
) {
    private enum class State { REQUESTED, ATTEMPTED }

    private data class Entry(val state: State, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun request(username: String) {
        val now = clock.instant()
        entries.values.removeIf { it.expiresAt <= now }
        entries[key(username)] = Entry(State.REQUESTED, now + ttl)
    }

    /** Whether this connection must use Mojang; marks the request as attempted, so only one connection does. */
    fun consume(username: String): Boolean {
        var consumed = false
        entries.computeIfPresent(key(username)) { _, entry ->
            when {
                entry.expiresAt <= clock.instant() -> null
                entry.state == State.REQUESTED -> Entry(State.ATTEMPTED, clock.instant() + ttl).also { consumed = true }
                else -> entry
            }
        }
        return consumed
    }

    /** Whether the player tried a licensed login that did not work out; forgets the attempt. */
    fun failed(username: String): Boolean {
        val entry = entries.remove(key(username)) ?: return false
        return entry.state == State.ATTEMPTED && entry.expiresAt > clock.instant()
    }

    fun clear(username: String) {
        entries.remove(key(username))
    }

    private fun key(username: String) = username.lowercase(Locale.ROOT)
}
