package ru.cororo.authserver.storage

import java.util.Locale

/** Email address checks shared by the server commands and the importer. */
object EmailAddresses {
    /** Deliberately simple: one `@`, a dotted domain, no spaces; the confirmation code proves the rest. */
    private val PATTERN = Regex("[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+")

    /** Placeholders other plugins store instead of a real address. */
    private val PLACEHOLDERS = setOf("your@email.com", "your@email.here")

    fun normalise(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.length > 254 || !PATTERN.matches(trimmed)) return null
        val (local, domain) = trimmed.split('@')
        return "$local@${domain.lowercase(Locale.ROOT)}".takeUnless { it.lowercase(Locale.ROOT) in PLACEHOLDERS }
    }

    /** `st****@example.com`, for messages that must not reveal the whole address. */
    fun mask(address: String): String {
        val local = address.substringBefore('@')
        val shown = local.take(if (local.length > 4) 2 else 1)
        return shown + "*".repeat((local.length - shown.length).coerceIn(1, 6)) + "@" + address.substringAfter('@')
    }
}
