package ru.cororo.authserver.server.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.slf4j.LoggerFactory
import ru.cororo.authserver.api.command.CommandSource
import ru.cororo.authserver.server.AuthServerImpl

/** Reads commands from standard input; output goes to the log. */
class Console(private val server: AuthServerImpl) : CommandSource {
    private val logger = LoggerFactory.getLogger("Console")

    override val name: String = "CONSOLE"

    override fun sendMessage(message: Component) = logger.info(PlainTextComponentSerializer.plainText().serialize(message))

    fun start() {
        Thread({
            val input = System.`in`.bufferedReader()
            while (true) {
                val line = runCatching { input.readLine() }.getOrNull() ?: break
                if (line.isBlank()) continue
                if (!server.commands.dispatch(this, line)) sendMessage(Component.text("Unknown command. Type 'help'."))
            }
        }, "console").apply { isDaemon = true }.start()
    }
}
