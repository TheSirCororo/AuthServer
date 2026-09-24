package ru.cororo.authserver.storage.password

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hash formats of other authentication plugins, kept so imported accounts can log in once and be rehashed.
 * Formats without a recognisable prefix of their own are stored with a `{TAG}` prefix by the importer.
 */
internal object LegacyHashes {
    /** `true`/`false` for a recognised format, `null` when the format is unknown. */
    fun verify(password: String, hash: String): Boolean? = when {
        // AuthMe SHA256, BungeeAuth type 5: $SHA$salt$sha256(sha256(password) + salt)
        hash.startsWith("\$SHA\$") -> hash.split('$').takeIf { it.size == 4 }?.let { equal(hex("SHA-256", hex("SHA-256", password) + it[2]), it[3]) } ?: false
        // nLogin: $SHA512$sha512(sha512(password) + salt)$salt
        hash.startsWith("\$SHA512\$") -> hash.split('$').takeIf { it.size == 4 }?.let { equal(hex("SHA-512", hex("SHA-512", password) + it[3]), it[2]) } ?: false
        hash.startsWith("pbkdf2_sha256\$") -> authMePbkdf2(password, hash)
        // AuthMe PBKDF2 with base64 salt: pbkdf2$rounds$salt$hash
        hash.startsWith("pbkdf2\$") -> hash.split('$').takeIf { it.size == 4 }?.let { parts ->
            pbkdf2("PBKDF2WithHmacSHA256", password, base64(parts[2]) ?: return false, parts[1].toIntOrNull() ?: return false,
                base64(parts[3]) ?: return false)
        } ?: false
        hash.startsWith(MD5) -> equal(hex("MD5", password), hash.removePrefix(MD5))
        hash.startsWith(SHA1) -> equal(hex("SHA-1", password), hash.removePrefix(SHA1))
        hash.startsWith(SHA256) -> equal(hex("SHA-256", password), hash.removePrefix(SHA256))
        hash.startsWith(SHA512) -> equal(hex("SHA-512", password), hash.removePrefix(SHA512))
        hash.startsWith(WHIRLPOOL) -> equal(Whirlpool.hex(password), hash.removePrefix(WHIRLPOOL))
        hash.startsWith(XAUTH) -> xAuth(password, hash.removePrefix(XAUTH))
        // BungeeAuth type 6: iterations:hex(salt):hex(PBKDF2WithHmacSHA1)
        hash.startsWith(PBKDF2_SHA1) -> hash.removePrefix(PBKDF2_SHA1).split(':').takeIf { it.size == 3 }?.let { parts ->
            pbkdf2("PBKDF2WithHmacSHA1", password, unhex(parts[1]) ?: return false, parts[0].toIntOrNull() ?: return false,
                unhex(parts[2]) ?: return false)
        } ?: false
        else -> null
    }

    const val MD5 = "{MD5}"
    const val SHA1 = "{SHA1}"
    const val SHA256 = "{SHA256}"
    const val SHA512 = "{SHA512}"
    const val WHIRLPOOL = "{WHIRLPOOL}"
    const val XAUTH = "{XAUTH}"
    const val PBKDF2_SHA1 = "{PBKDF2-SHA1}"
    val TAGS = listOf(MD5, SHA1, SHA256, SHA512, WHIRLPOOL, XAUTH, PBKDF2_SHA1)

    /** AuthMe writes hex keys (PBKDF2) and Django writes base64 keys under the same prefix. */
    private fun authMePbkdf2(password: String, hash: String): Boolean {
        val parts = hash.split('$')
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val key = if (parts[3].length == 128 && parts[3].all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) unhex(parts[3]) else base64(parts[3])
        return pbkdf2("PBKDF2WithHmacSHA256", password, parts[2].toByteArray(), iterations, key ?: return false)
    }

    /**
     * xAuth / BungeeAuth types 0 and 7: a 12-character salt is spliced into whirlpool(salt + password) at the
     * position given by the password length.
     */
    private fun xAuth(password: String, stored: String): Boolean {
        val position = if (password.length >= stored.length) stored.length - 1 else password.length
        if (position + 12 > stored.length) return false
        val salt = stored.substring(position, position + 12)
        val digest = Whirlpool.hex(salt + password)
        return equal(digest.substring(0, position) + salt + digest.substring(position), stored)
    }

    private fun pbkdf2(algorithm: String, password: String, salt: ByteArray, iterations: Int, expected: ByteArray): Boolean {
        if (iterations <= 0 || expected.isEmpty()) return false
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, expected.size * 8)
        return MessageDigest.isEqual(SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded, expected)
    }

    private fun hex(algorithm: String, value: String): String =
        MessageDigest.getInstance(algorithm).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun equal(expected: String, actual: String) =
        MessageDigest.isEqual(expected.lowercase().toByteArray(), actual.lowercase().toByteArray())

    private fun unhex(value: String): ByteArray? = runCatching {
        ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }.getOrNull()?.takeIf { value.length % 2 == 0 }

    private fun base64(value: String): ByteArray? = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
}
