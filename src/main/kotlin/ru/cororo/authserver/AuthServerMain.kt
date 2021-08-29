@file:JvmName("AuthServerMain")
package ru.cororo.authserver

/**
 * Auth server main method (used if you don't use AuthServer as Velocity plugin)
 */
suspend fun main(args: Array<out String>) {
    val hostname = if (args.isNotEmpty()) args[0] else "0.0.0.0"
    val port = try {
        if (args.size == 2) args[1].toInt() else 5000
    } catch (ex: NumberFormatException) {
        5000
    }

    AuthServer.start(hostname, port)
}