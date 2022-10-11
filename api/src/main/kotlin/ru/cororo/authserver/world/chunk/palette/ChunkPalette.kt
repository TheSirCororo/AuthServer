package ru.cororo.authserver.world.chunk.palette

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.world.block.BlockState

interface ChunkPalette {
    val bitsPerBlock: Byte

    fun getIdForState(state: BlockState): Int
    fun getStateForId(id: Int): BlockState?
    fun read(buffer: ByteBuf)
    fun write(buffer: ByteBuf)
}