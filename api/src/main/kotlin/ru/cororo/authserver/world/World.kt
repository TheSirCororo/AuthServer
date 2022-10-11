package ru.cororo.authserver.world

import ru.cororo.authserver.NameHolder
import ru.cororo.authserver.world.chunk.Chunk

/**
 * World`s class. Used for making world manipulations
 */
interface World : NameHolder {
    /**
     * World name
     */
    override val name: String

    val loadedChunks: List<Chunk>
}