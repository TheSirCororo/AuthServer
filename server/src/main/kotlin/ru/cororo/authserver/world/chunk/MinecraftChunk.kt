package ru.cororo.authserver.world.chunk

class MinecraftChunk(
    override val x: Int,
    override val z: Int
) : Chunk {
    override val sections = mutableListOf<ChunkSection>()

}