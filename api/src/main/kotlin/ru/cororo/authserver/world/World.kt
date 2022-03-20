package ru.cororo.authserver.world

import ru.cororo.authserver.NameHolder

/**
 * World`s class. Used for making world manipulations
 */
interface World : NameHolder {
    /**
     * World name
     */
    override val name: String
}