package ru.cororo.authserver.server.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.cororo.authserver.bridge.PlayerStatus
import ru.cororo.authserver.server.config.PremiumPolicy
import ru.cororo.authserver.storage.AccountRepository

/**
 * Decides whether a connection authenticates through Mojang. Existing accounts keep the mode they were created with
 * (or switched to with `/premium`): licensed accounts always use Mojang, password accounts never do. New names follow
 * the configured [PremiumPolicy].
 *
 * Called once per connection attempt (at the proxy's pre-login or the standalone login), as it consumes
 * [LicensedLoginRequests].
 */
class PremiumResolver(
    /** When licensed login is disabled every name uses a password. */
    private val licensedLogin: Boolean,
    private val policy: PremiumPolicy,
    private val accounts: AccountRepository,
    private val mojang: MojangApi,
    private val licensedRequests: LicensedLoginRequests,
) {
    suspend fun status(username: String): PlayerStatus {
        val account = withContext(Dispatchers.IO) { accounts.find(username) }
        if (account != null) return PlayerStatus(true, account.premium, account.username, licensedLogin && account.premium)
        val online = licensedLogin && when (policy) {
            PremiumPolicy.MANUAL -> licensedRequests.consume(username)
            PremiumPolicy.OFFLINE -> false
            PremiumPolicy.ONLINE -> true
            // If Mojang cannot be asked the name registers offline; it has no account yet, so nothing is at risk.
            PremiumPolicy.AUTO -> mojang.accountExists(username) == true
        }
        return PlayerStatus(false, false, null, online)
    }
}
