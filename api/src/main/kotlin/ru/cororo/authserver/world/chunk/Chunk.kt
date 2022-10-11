package ru.cororo.authserver.world.chunk

interface Chunk {
    val x: Int
    val z: Int
    val sections: List<ChunkSection>

    companion object {
        const val WIDTH = 16
        const val HEIGHT = 16
        const val DEPTH = 256
        const val SECTION_DEPTH = 16
        const val SECTION_COUNT = DEPTH / SECTION_DEPTH
        val EMPTY_LIGHT_DATA = ByteArray(2048)
    }
}