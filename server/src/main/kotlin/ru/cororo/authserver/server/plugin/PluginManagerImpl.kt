package ru.cororo.authserver.server.plugin

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import ru.cororo.authserver.api.AuthServer
import ru.cororo.authserver.api.plugin.Plugin
import ru.cororo.authserver.api.plugin.PluginDescription
import ru.cororo.authserver.api.plugin.PluginManager
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.name

/** Plugin classes see the server, their own jar and the jars of the plugins they depend on. */
internal class PluginClassLoader(url: URL, parent: ClassLoader, private val dependencies: List<PluginClassLoader>) :
    URLClassLoader(arrayOf(url), parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = try {
        super.loadClass(name, resolve)
    } catch (missing: ClassNotFoundException) {
        dependencies.firstNotNullOfOrNull { runCatching { it.loadOwn(name) }.getOrNull() } ?: throw missing
    }

    private fun loadOwn(name: String): Class<*> = synchronized(getClassLoadingLock(name)) { findLoadedClass(name) ?: findClass(name) }

    companion object {
        init {
            registerAsParallelCapable()
        }
    }
}

class PluginManagerImpl(
    private val directory: Path,
    private val onDisabled: (Plugin) -> Unit,
) : PluginManager {
    private val logger = LoggerFactory.getLogger(PluginManagerImpl::class.java)
    private val loaded = LinkedHashMap<String, Plugin>()
    private val enabled = LinkedHashSet<String>()
    private val loaders = ArrayList<PluginClassLoader>()

    override val plugins: Collection<Plugin> get() = loaded.values

    override fun plugin(id: String): Plugin? = loaded[id]

    override fun isEnabled(id: String): Boolean = id in enabled

    /** Discovers, orders, instantiates and loads every plugin jar. Broken plugins are skipped with an error. */
    fun load(server: AuthServer) {
        Files.createDirectories(directory)
        val candidates = Files.list(directory).use { files -> files.filter { it.extension == "jar" }.sorted().toList() }
            .mapNotNull { jar -> runCatching { jar to describe(jar) }.onFailure { logger.error("Skipping {}: {}", jar.name, it.message) }.getOrNull() }
            .associateBy { it.second.id }
        for (id in order(candidates.mapValues { it.value.second })) {
            val (jar, description) = candidates.getValue(id)
            try {
                val dependencies = (description.depends + description.softDepends).mapNotNull { dependency ->
                    loaders.firstOrNull { loader -> loaded[dependency]?.javaClass?.classLoader === loader }
                }
                val loader = PluginClassLoader(jar.toUri().toURL(), javaClass.classLoader, dependencies).also(loaders::add)
                val plugin = loader.loadClass(description.main).asSubclass(Plugin::class.java).getDeclaredConstructor().newInstance()
                plugin.initialize(server, description, LoggerFactory.getLogger(description.name), directory.resolve(description.id))
                plugin.onLoad()
                loaded[description.id] = plugin
                logger.info("Loaded {} {}", description.name, description.version)
            } catch (exception: Throwable) {
                logger.error("Could not load plugin {}", description.id, exception)
            }
        }
    }

    fun enableAll() {
        for ((id, plugin) in loaded) {
            val missing = plugin.description.depends.filterNot { it in enabled }
            if (missing.isNotEmpty()) {
                logger.error("Not enabling {}: dependencies {} are not enabled", id, missing)
                continue
            }
            try {
                plugin.onEnable()
                enabled += id
            } catch (exception: Throwable) {
                logger.error("Could not enable {}", id, exception)
                onDisabled(plugin)
            }
        }
    }

    fun disableAll() {
        for (id in enabled.reversed()) {
            val plugin = loaded.getValue(id)
            try {
                plugin.onDisable()
            } catch (exception: Throwable) {
                logger.error("Error while disabling {}", id, exception)
            }
            onDisabled(plugin)
        }
        enabled.clear()
        loaders.forEach(URLClassLoader::close)
    }

    /** JSON shape of the descriptor; every field is optional here and validated in [describe]. */
    private class RawDescription(
        val id: String? = null,
        val name: String? = null,
        val version: String? = null,
        val main: String? = null,
        val depends: List<String>? = null,
        val softDepends: List<String>? = null,
        val authors: List<String>? = null,
        val description: String? = null,
    )

    private fun describe(jar: Path): PluginDescription = JarFile(jar.toFile()).use { file ->
        val entry = file.getJarEntry(DESCRIPTOR) ?: error("no $DESCRIPTOR")
        val raw = file.getInputStream(entry).bufferedReader().use { gson.fromJson(it, RawDescription::class.java) }
        val id = requireNotNull(raw.id) { "no plugin id" }
        require(id.matches(ID)) { "invalid plugin id '$id'" }
        PluginDescription(
            id = id,
            name = raw.name ?: id,
            version = raw.version ?: "unknown",
            main = requireNotNull(raw.main) { "no main class" },
            depends = raw.depends.orEmpty(),
            softDepends = raw.softDepends.orEmpty(),
            authors = raw.authors.orEmpty(),
            description = raw.description.orEmpty(),
        )
    }

    /** Topological order; plugins with missing hard dependencies or in cycles are left out. */
    private fun order(descriptions: Map<String, PluginDescription>): List<String> {
        val result = LinkedHashSet<String>()
        val visiting = HashSet<String>()
        fun visit(id: String): Boolean {
            if (id in result) return true
            val description = descriptions[id] ?: return false
            if (!visiting.add(id)) {
                logger.error("Dependency cycle through {}", id)
                return false
            }
            val missing = description.depends.filterNot(::visit)
            description.softDepends.filter(descriptions::containsKey).forEach(::visit)
            visiting -= id
            if (missing.isNotEmpty()) {
                logger.error("Not loading {}: missing dependencies {}", id, missing)
                return false
            }
            result += id
            return true
        }
        descriptions.keys.sorted().forEach(::visit)
        return result.toList()
    }

    private companion object {
        const val DESCRIPTOR = "authserver-plugin.json"
        val ID = Regex("[a-z0-9_-]{1,64}")
        val gson = Gson()
    }
}
