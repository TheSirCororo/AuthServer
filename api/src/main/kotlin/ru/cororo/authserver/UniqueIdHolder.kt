package ru.cororo.authserver

import java.util.*

/**
 * Any entity or block or other that have UUID
 */
interface UniqueIdHolder {
    val uniqueId: UUID
}