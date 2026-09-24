package ru.cororo.authserver.storage.password

import com.password4j.BcryptFunction
import com.password4j.Password
import com.password4j.types.Bcrypt
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordHasherTest {
    private val hasher = PasswordHasher(Argon2Settings(memoryKib = 1024, iterations = 1))

    @Test
    fun `argon2id hashes verify and are salted`() {
        val first = hasher.hash("correct horse")
        val second = hasher.hash("correct horse")
        assertTrue(first.startsWith("\$argon2id\$"))
        assertTrue(first != second, "Every hash has its own salt")
        assertEquals(Verification(valid = true, needsRehash = false), hasher.verify("correct horse", first))
        assertFalse(hasher.verify("wrong", first).valid)
    }

    @Test
    fun `changed parameters request a rehash`() {
        val old = PasswordHasher(Argon2Settings(memoryKib = 512, iterations = 1)).hash("secret")
        assertEquals(Verification(valid = true, needsRehash = true), hasher.verify("secret", old))
    }

    @Test
    fun `authme sha256 hashes are accepted and upgraded`() {
        val salt = "0123456789abcdef"
        val hash = "\$SHA\$$salt\$" + sha256(sha256("qwerty123") + salt)
        assertEquals(Verification(valid = true, needsRehash = true), hasher.verify("qwerty123", hash))
        assertFalse(hasher.verify("qwerty124", hash).valid)
    }

    @Test
    fun `authme bcrypt and pbkdf2 hashes are accepted`() {
        val bcrypt = Password.hash("password").with(BcryptFunction.getInstance(Bcrypt.A, 6)).result
        assertEquals(Verification(valid = true, needsRehash = true), hasher.verify("password", bcrypt))
        assertTrue(hasher.verify("password", "pbkdf2_sha256\$1000\$salt\$" + pbkdf2("password", "salt", 1000)).valid)
        assertFalse(hasher.verify("password", "unknown-format").valid)
    }

    private fun sha256(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun pbkdf2(password: String, salt: String, iterations: Int): String {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt.toByteArray(), iterations, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return java.util.Base64.getEncoder().encodeToString(key)
    }
}
