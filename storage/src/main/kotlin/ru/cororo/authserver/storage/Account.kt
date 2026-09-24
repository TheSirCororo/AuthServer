package ru.cororo.authserver.storage

import java.time.Instant
import java.util.UUID

/**
 * A registered player.
 *
 * @property username name with the case the player registered with; lookups are case-insensitive
 * @property passwordHash encoded hash; `null` for premium-only accounts that never set a password
 * @property premium whether the account authenticates through Mojang instead of a password
 * @property premiumUuid Mojang UUID bound to the account once it logged in as premium
 */
data class Account(
    val id: Long,
    val username: String,
    val passwordHash: String?,
    val premium: Boolean,
    val premiumUuid: UUID?,
    val registeredAt: Instant,
    val registrationIp: String?,
    val lastLoginAt: Instant?,
    val lastLoginIp: String?,
) {
    val usernameLower: String get() = username.lowercase()
}

/** A complete account record, e.g. from another plugin's database. */
data class AccountRecord(
    val username: String,
    val passwordHash: String?,
    val premium: Boolean = false,
    val premiumUuid: UUID? = null,
    val registeredAt: Instant = Instant.now(),
    val registrationIp: String? = null,
    val lastLoginAt: Instant? = null,
    val lastLoginIp: String? = null,
)

class AccountExistsException(username: String) : RuntimeException("Account $username already exists")

/** Blocking account storage; call it from an I/O dispatcher. */
interface AccountRepository {
    fun find(username: String): Account?

    fun findByPremiumUuid(uuid: UUID): Account?

    /** @throws AccountExistsException when the name (ignoring case) is taken */
    fun create(username: String, passwordHash: String?, ip: String?, premium: Boolean = false, premiumUuid: UUID? = null): Account =
        insert(AccountRecord(username, passwordHash, premium, premiumUuid, registrationIp = ip))

    /** @throws AccountExistsException when the name (ignoring case) is taken */
    fun insert(record: AccountRecord): Account

    fun updatePassword(id: Long, passwordHash: String)

    fun recordLogin(id: Long, ip: String?, at: Instant = Instant.now())

    /** Forgets the last login so the session cannot be resumed. */
    fun clearSession(id: Long)

    fun setPremium(id: Long, premium: Boolean, premiumUuid: UUID?)

    fun delete(id: Long)

    fun countByRegistrationIp(ip: String): Int
}
