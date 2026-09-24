@file:JvmName("ProbeMain")

package ru.cororo.authserver.probe

import ru.cororo.authserver.protocol.ProtocolVersion
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  record <protocol> <host> <port> <output dir> [play seconds] [username] [command;command...]
  verify <protocol> <recording dir>
  extract <protocol> <recording dir> <output dir>"""

fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val version = requireNotNull(ProtocolVersion.byProtocol(args[1].toInt())) { "Unsupported protocol ${args[1]}" }
    when (args[0]) {
        "record" -> ProbeSession(
            version, args[2], args[3].toInt(), Path.of(args[4]), args.getOrNull(5)?.toLong() ?: 8,
            username = args.getOrNull(6) ?: ProbeSession.USERNAME,
            customCommands = args.getOrNull(7)?.split(';'),
        ).run()
        "verify" -> {
            val problems = ProbeVerifier(version, Path.of(args[2])).verify()
            problems.forEach(System.err::println)
            if (problems.isNotEmpty()) exitProcess(1)
        }
        "extract" -> ProbeExtractor(version, Path.of(args[2]), Path.of(args[3])).extract()
        else -> {
            System.err.println(USAGE)
            exitProcess(2)
        }
    }
}
