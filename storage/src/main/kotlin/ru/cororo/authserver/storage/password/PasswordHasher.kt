package ru.cororo.authserver.storage.password

import com.password4j.Argon2Function
import com.password4j.BcryptFunction
import com.password4j.Password
import com.password4j.types.Argon2

/** Outcome of checking a password against a stored hash. */
data class Verification(val valid: Boolean, val needsRehash: Boolean = false) {
    companion object {
        val INVALID = Verification(false)
    }
}

/**
 * Argon2id parameters; defaults follow OWASP's minimum recommendation (19 MiB, 2 passes, 1 lane).
 * @property memoryKib memory cost in KiB
 */
data class Argon2Settings(val memoryKib: Int = 19456, val iterations: Int = 2, val parallelism: Int = 1)

/** https://www.youtube.com/watch?v=VsCMwP81vJQ**
 * New passwords are hashed with Argon2id (PHC string format). BCrypt, Argon2 and the formats of other auth plugins
 * ([LegacyHashes]) are still accepted so imported databases keep working; those - and Argon2 hashes with outdated
 * parameters - are reported as needing a rehash.
 */
class PasswordHasher(private val settings: Argon2Settings = Argon2Settings()) {
    private val argon2 = Argon2Function.getInstance(settings.memoryKib, settings.iterations, settings.parallelism, 32, Argon2.ID)

    fun hash(password: String): String = Password.hash(password).addRandomSalt(16).with(argon2).result

    fun verify(password: String, hash: String): Verification = when {
        hash.startsWith("\$argon2") -> {
            val function = Argon2Function.getInstanceFromHash(hash)
            val valid = Password.check(password, hash).with(function)
            Verification(valid, valid && (function != argon2 || !hash.startsWith("\$argon2id\$")))
        }
        hash.startsWith("\$2a\$") || hash.startsWith("\$2b\$") || hash.startsWith("\$2y\$") ->
            legacy(Password.check(password, hash).with(BcryptFunction.getInstanceFromHash(hash)))
        else -> LegacyHashes.verify(password, hash)?.let(::legacy) ?: Verification.INVALID
    }

    private fun legacy(valid: Boolean) = Verification(valid, valid)
}
