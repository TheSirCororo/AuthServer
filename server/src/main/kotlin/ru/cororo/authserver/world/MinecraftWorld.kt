package ru.cororo.authserver.world

import ru.cororo.authserver.world.chunk.Chunk

data class MinecraftWorld(
    override val name: String
) : World {
    override val loadedChunks = mutableListOf<Chunk>()
}