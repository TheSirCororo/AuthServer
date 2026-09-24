@file:JvmName("AuthServerMain")

package ru.cororo.authserver

import org.slf4j.LoggerFactory
import ru.cororo.authserver.server.AuthServerImpl
import ru.cororo.authserver.server.config.ServerConfig
import java.nio.file.Path
import kotlin.system.exitProcess

/** `java -jar authserver.jar [--config path/to/config.yml]`; files are resolved next to the config. */
fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("AuthServer")
    val configPath = args.indexOf("--config").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: "config.yml"
    val path = Path.of(configPath).toAbsolutePath()
    val server = try {
        AuthServerImpl(ServerConfig.load(path), path.parent)
    } catch (exception: Exception) {
        logger.error("Could not start: {}", exception.message, exception)
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread(server::stop, "shutdown"))
    server.start()
}
