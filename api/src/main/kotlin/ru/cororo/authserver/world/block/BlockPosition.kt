package ru.cororo.authserver.world.block

import ru.cororo.authserver.world.Position
import ru.cororo.authserver.world.World

data class BlockPosition(
    val world: World,
    val x: Int,
    val y: Int,
    val z: Int
) {
    fun toPosition() = Position(world, x.toDouble(), y.toDouble(), z.toDouble())
}