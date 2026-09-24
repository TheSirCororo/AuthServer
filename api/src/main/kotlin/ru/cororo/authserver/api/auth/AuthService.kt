package ru.cororo.authserver.api.auth

import ru.cororo.authserver.api.player.Player
import ru.cororo.authserver.bridge.AuthMethod
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class AuthState {
    /** Joined the limbo and has to log in or register. */
    UNAUTHENTICATED,
    AUTHENTICATED,
}

/** Read-only view of a stored account. */
data class AccountInfo(
    val username: String,
    val premium: Boolean,
    val premiumUuid: UUID?,
    val hasPassword: Boolean,
    val registeredAt: Instant,
    val lastLoginAt: Instant?,
    val lastLoginIp: String?,
)

/** Result of an account operation started by a plugin. */
sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val reason: String) : AuthResult
}

/**
 * Account and authentication operations. Methods returning futures touch the database or hash passwords
 * and complete off the calling thread.
 */
interface AuthService {
    fun account(username: String): CompletableFuture<AccountInfo?>

    /** Creates an offline account as if the player had used `/register`. */
    fun register(username: String, password: String): CompletableFuture<AuthResult>

    fun changePassword(username: String, password: String): CompletableFuture<AuthResult>

    fun unregister(username: String): CompletableFuture<AuthResult>

    /** Marks an account as licensed so it logs in through Mojang, or back to a password account. */
    fun setPremium(username: String, premium: Boolean): CompletableFuture<AuthResult>

    /** Authenticates a player without a password, e.g. after a plugin-provided check. */
    fun forceLogin(player: Player, method: AuthMethod = AuthMethod.FORCED)
}
