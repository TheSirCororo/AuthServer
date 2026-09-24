package ru.cororo.authserver.server.auth.security

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TotpTest {
    /** The SHA-1 seed of RFC 6238 appendix B, "12345678901234567890". */
    private val secret = Base32.encode("12345678901234567890".toByteArray())

    @Test
    fun `codes match the RFC 6238 test vectors`() {
        val vectors = mapOf(59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471",
            1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130")
        for ((seconds, expected) in vectors) {
            assertEquals(expected, Totp.code(secret, Totp.step(Instant.ofEpochSecond(seconds)), digits = 8), "T=$seconds")
        }
    }

    @Test
    fun `codes are accepted within one step of drift`() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val step = Totp.step(now)
        assertEquals(step, Totp.matchingStep(secret, Totp.code(secret, step), now))
        assertEquals(step - 1, Totp.matchingStep(secret, Totp.code(secret, step - 1), now))
        assertEquals(step + 1, Totp.matchingStep(secret, Totp.code(secret, step + 1).chunked(3).joinToString(" "), now))
        assertNull(Totp.matchingStep(secret, Totp.code(secret, step - 2), now))
        assertNull(Totp.matchingStep(secret, "12345", now))
        assertNull(Totp.matchingStep(secret, "abcdef", now))
    }

    @Test
    fun `base32 round trips and matches RFC 4648`() {
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
        assertContentEquals("foobar".toByteArray(), Base32.decode("mzxw 6ytb oi======"))
        val secret = Totp.generateSecret()
        assertEquals(16, secret.length)
        assertContentEquals(Base32.decode(secret), Base32.decode(secret.lowercase()))
    }

    @Test
    fun `the uri follows the key uri format`() {
        assertEquals(
            "otpauth://totp/My%20Server:Steve?secret=ABC&issuer=My%20Server&algorithm=SHA1&digits=6&period=30",
            Totp.uri("My Server", "Steve", "ABC"),
        )
    }
}
