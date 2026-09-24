package ru.cororo.authserver.api.world

import ru.cororo.authserver.api.player.Position

/** The world players wait in. It is loaded at startup and does not change while the server runs. */
interface LimboWorld {
    val spawn: Position

    /** Block state at a position as a string such as `minecraft:oak_stairs[facing=east,...]`. */
    fun blockAt(x: Int, y: Int, z: Int): String

    /** Whether the position is inside a solid block (useful for simple movement checks). */
    fun isSolid(x: Int, y: Int, z: Int): Boolean
}
