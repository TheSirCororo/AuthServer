package ru.cororo.authserver.server.plugin

import org.junit.jupiter.api.io.TempDir
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_3
import ru.cororo.authserver.server.TestServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Loads the real example plugin jar (examples/example-plugin) into a server. */
class ExamplePluginTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `example plugin works end to end`() {
        val jar = Path.of(System.getProperty("examplePluginJar"))
        Files.copy(jar, Files.createDirectories(directory.resolve("plugins")).resolve("example.jar"))
        TestServer(directory = directory).use { test ->
            assertTrue(test.server.plugins.isEnabled("example"))
            test.client(MINECRAFT_1_20_3, "AdminBob").use { client ->
                client.connect()
                client.awaitChat { "Welcome, AdminBob!" in it }
                client.await { client.windows[0]?.get(44) != null }
                client.command("hello there")
                client.awaitChat { "Hello, there!" in it }
                client.command("register secret-pass secret-pass")
                client.awaitChat { "reserved" in it }
            }
            assertEquals(null, test.server.auth.account("AdminBob").get(), "The plugin refused the registration")
        }
    }
}
