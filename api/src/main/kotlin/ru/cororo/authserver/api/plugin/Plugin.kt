package ru.cororo.authserver.api.plugin

import org.slf4j.Logger
import ru.cororo.authserver.api.AuthServer
import java.nio.file.Path

/**
 * Contents of `authserver-plugin.json` at the root of a plugin jar:
 * ```json
 * {"id": "welcome", "name": "Welcome", "version": "1.0", "main": "com.example.WelcomePlugin",
 *  "depends": [], "softDepends": [], "authors": ["you"], "description": "Greets players"}
 * ```
 * [id] must be lowercase letters, digits, `-` and `_`.
 */
data class PluginDescription(
    val id: String,
    val name: String = id,
    val version: String = "unknown",
    val main: String,
    val depends: List<String> = emptyList(),
    val softDepends: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val description: String = "",
)

/**
 * Base class of auth server plugins. The main class needs a public no-argument constructor.
 * Lifecycle: [onLoad] for every plugin, then [onEnable] in dependency order; [onDisable] in reverse on shutdown.
 */
abstract class Plugin {
    lateinit var server: AuthServer
        private set
    lateinit var description: PluginDescription
        private set
    lateinit var logger: Logger
        private set

    /** `plugins/<id>/`, created on demand. */
    lateinit var dataDirectory: Path
        private set

    open fun onLoad() = Unit

    open fun onEnable() = Unit

    open fun onDisable() = Unit

    /** Called by the plugin manager before [onLoad]. */
    fun initialize(server: AuthServer, description: PluginDescription, logger: Logger, dataDirectory: Path) {
        check(!this::server.isInitialized) { "Plugin ${description.id} is already initialized" }
        this.server = server
        this.description = description
        this.logger = logger
        this.dataDirectory = dataDirectory
    }
}

interface PluginManager {
    val plugins: Collection<Plugin>

    fun plugin(id: String): Plugin?

    fun isEnabled(id: String): Boolean
}
