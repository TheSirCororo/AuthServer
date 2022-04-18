package ru.cororo.authserver.player

import ru.cororo.authserver.UniqueIdHolder
import java.util.*

/**
 * Player game profile
 */
data class GameProfile(
    internal val id: String,
    val name: String,
    val properties: MutableList<Property>
) : UniqueIdHolder {
    override val uniqueId: UUID
        get() = UUID.fromString(
            id.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(),
                "$1-$2-$3-$4-$5"
            )
        )
}

/**
 * Game profile property
 */
data class Property(
    val name: String,
    val value: String,
    val sign: String?
)