package ru.cororo.authserver.server.plugin

import org.junit.jupiter.api.io.TempDir
import ru.cororo.authserver.api.command.Command
import ru.cororo.authserver.api.command.CommandExecutor
import ru.cororo.authserver.api.event.PlayerJoinEvent
import ru.cororo.authserver.api.event.ServerStartedEvent
import ru.cororo.authserver.api.event.on
import ru.cororo.authserver.api.plugin.Plugin
import ru.cororo.authserver.server.TestServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records lifecycle calls of every test plugin, in order. */
object Lifecycle {
    val calls = mutableListOf<String>()
}

open class RecordingPlugin : Plugin() {
    override fun onLoad() {
        Lifecycle.calls += "load:${description.id}"
    }

    override fun onEnable() {
        Lifecycle.calls += "enable:${description.id}"
        server.events.on<ServerStartedEvent>(this) { Lifecycle.calls += "started:${description.id}" }
        server.commands.register(this, Command(name = "hello-${description.id}", executor = CommandExecutor { _, _ -> }))
    }

    override fun onDisable() {
        Lifecycle.calls += "disable:${description.id}"
    }
}

class BasePlugin : RecordingPlugin()

class AddonPlugin : RecordingPlugin()

class PluginManagerTest {
    @TempDir
    lateinit var directory: Path

    private fun jar(name: String, descriptor: String) {
        val plugins = Files.createDirectories(directory.resolve("plugins"))
        JarOutputStream(Files.newOutputStream(plugins.resolve("$name.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("authserver-plugin.json"))
            jar.write(descriptor.toByteArray())
            jar.closeEntry()
        }
    }

    @Test
    fun `plugins load in dependency order and clean up when disabled`() {
        Lifecycle.calls.clear()
        // The addon sorts first by name but depends on the base plugin.
        jar("a-addon", """{"id": "addon", "main": "${AddonPlugin::class.java.name}", "depends": ["base"]}""")
        jar("b-base", """{"id": "base", "name": "Base", "version": "1.2", "main": "${BasePlugin::class.java.name}"}""")
        jar("broken", """{"id": "broken", "main": "com.example.Missing"}""")
        jar("orphan", """{"id": "orphan", "main": "${BasePlugin::class.java.name}", "depends": ["nothing"]}""")
        jar("cycle-a", """{"id": "cycle-a", "main": "${BasePlugin::class.java.name}", "depends": ["cycle-b"]}""")
        jar("cycle-b", """{"id": "cycle-b", "main": "${BasePlugin::class.java.name}", "depends": ["cycle-a"]}""")
        jar("invalid", """{"id": "Not Valid", "main": "x"}""")

        val test = TestServer(directory = directory)
        val plugins = test.server.plugins
        assertEquals(listOf("base", "addon"), plugins.plugins.map { it.description.id })
        assertEquals("1.2", plugins.plugin("base")?.description?.version)
        assertTrue(plugins.isEnabled("addon"))
        assertEquals(
            listOf("load:base", "load:addon", "enable:base", "enable:addon", "started:base", "started:addon"),
            Lifecycle.calls,
        )
        assertNotNull(test.server.commands.command("hello-addon"))

        test.close()
        assertEquals(listOf("disable:addon", "disable:base"), Lifecycle.calls.takeLast(2))
        assertNull(test.server.commands.command("hello-addon"), "Commands are removed with their plugin")
        assertFalse(test.server.events.hasSubscribers(ServerStartedEvent::class.java))
        assertFalse(test.server.events.hasSubscribers(PlayerJoinEvent::class.java))
    }
}
