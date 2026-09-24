package ru.cororo.authserver.server.auth.security

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords (RFC 6238) as authenticator apps use them: HMAC-SHA1, 30-second steps, 6 digits.
 * Secrets are Base32 strings.
 */
object Totp {
    const val DIGITS = 6
    const val STEP_SECONDS = 30L
    /** 80 bits, 16 characters: the usual size, short enough to type into a phone. */
    private const val SECRET_BYTES = 10
    private val random = SecureRandom()

    fun generateSecret(): String = Base32.encode(ByteArray(SECRET_BYTES).also(random::nextBytes))

    fun step(at: Instant): Long = at.epochSecond / STEP_SECONDS

    /** The code of time step [step]; [digits] other than 6 exist for the RFC's test vectors. */
    fun code(secret: String, step: Long, digits: Int = DIGITS): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(Base32.decode(secret), "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff
        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /**
     * The time step [code] belongs to, allowing [window] steps of clock drift either way, or `null`. Callers reject
     * steps they have already accepted, so a code cannot be used twice.
     */
    fun matchingStep(secret: String, code: String, at: Instant, window: Int = 1): Long? {
        val normalised = code.filterNot(Char::isWhitespace)
        if (normalised.length != DIGITS || !normalised.all(Char::isDigit)) return null
        val now = step(at)
        return (now - window..now + window).firstOrNull { step ->
            MessageDigest.isEqual(code(secret, step).toByteArray(), normalised.toByteArray())
        }
    }

    /** `otpauth://` link understood by authenticator apps (and shown as a QR code by most of them). */
    fun uri(issuer: String, account: String, secret: String): String {
        fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${encode(issuer)}:${encode(account)}?secret=$secret&issuer=${encode(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }
}

/** RFC 4648 Base32 without padding, the alphabet of authenticator secrets. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
    }

    /** Ignores case, spaces and padding, as people type secrets by hand. */
    fun decode(text: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in text.uppercase()) {
            if (char == '=' || char.isWhitespace() || char == '-') continue
            val value = ALPHABET.indexOf(char)
            require(value >= 0) { "Not a Base32 character: $char" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                output.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return output.toByteArray()
    }
}
