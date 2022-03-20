package ru.cororo.authserver.world.block

import ru.cororo.authserver.world.Locatable
import ru.cororo.authserver.world.Position

/**
 * Block state (its type, position, block data and state)
 */
interface BlockState : Locatable {
    /**
     * Block's position
     */
    override val position: Position get() = blockPos.toPosition()

    /**
     * Block's position as [BlockPosition]
     */
    val blockPos: BlockPosition
}