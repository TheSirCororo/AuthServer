package ru.cororo.authserver.world.chunk.palette

import io.netty.buffer.ByteBuf
import ru.cororo.authserver.util.readVarInt
import ru.cororo.authserver.world.block.BlockState
import ru.cororo.authserver.world.chunk.Chunk

class IndirectPalette(
    override val bitsPerBlock: Byte,
    val chunk: Chunk
) : ChunkPalette {
    private val stateById = mutableMapOf<Int, BlockState>()
    private val idByState = mutableMapOf<BlockState, Int>()

    override fun getIdForState(state: BlockState): Int = idByState[state] ?: -1

    override fun getStateForId(id: Int): BlockState? = stateById[id]

    override fun read(buffer: ByteBuf) {
        stateById.clear()
        idByState.clear()

        val length = buffer.readVarInt()
        for (i in 0 until length) {
            val stateId = buffer.readVarInt()
            val state = TODO()
        }
    }

    override fun write(buffer: ByteBuf) {
        TODO("Not yet implemented")
    }
}