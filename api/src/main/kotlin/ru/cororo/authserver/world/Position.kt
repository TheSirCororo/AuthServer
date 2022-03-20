package ru.cororo.authserver.world

import ru.cororo.authserver.world.block.BlockPosition

/**
 * Position of block, player or other thing
 */
data class Position(
    var world: World? = null,
    var x: Double,
    var y: Double,
    var z: Double,
    var yaw: Float = 0.0f,
    var pitch: Float = 0.0f
) {
    /**
     * Convert position to block position. Requires to world not be null
     * @throws NullPointerException
     */
    fun toBlockPosition() = BlockPosition(world!!, x.toInt(), y.toInt(), z.toInt())
}