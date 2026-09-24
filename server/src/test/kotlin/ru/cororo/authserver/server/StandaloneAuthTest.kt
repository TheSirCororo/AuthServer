package ru.cororo.authserver.server

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.*
import ru.cororo.authserver.server.config.PremiumPolicy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Test bodies are blocks on purpose: JUnit silently skips test methods that return a value.
class StandaloneAuthTest {
    @ParameterizedTest
    @EnumSource(names = ["MINECRAFT_1_8", "MINECRAFT_1_12_2", "MINECRAFT_1_13_2", "MINECRAFT_1_16_4", "MINECRAFT_1_18_2",
        "MINECRAFT_1_19_1", "MINECRAFT_1_20", "MINECRAFT_1_20_3", "MINECRAFT_1_21_4", "MINECRAFT_1_21_9", "MINECRAFT_26_2", "MINECRAFT_26_3"])
    fun `offline players register and log in`(version: ProtocolVersion) {
        TestServer().use { test ->
            test.client(version, "Steve").use { client ->
                client.connect()
                assertNotNull(client.joined, "Join packet")
                assertEquals(version >= MINECRAFT_1_20_5, client.encrypted, "Offline 1.20.5+ connections are encrypted")
                client.awaitChat { "/register" in it }
                client.command("register short short")
                client.awaitChat { "at least" in it }
                client.command("register secret-pass secret-pass")
                client.awaitChat { "registered" in it }
            }
            waitUntil { test.server.player("Steve") == null }
            test.client(version, "Steve").use { client ->
                client.connect()
                client.awaitChat { "/login" in it }
                client.command("login wrong-pass")
                client.awaitChat { "Wrong password" in it }
                client.command("l secret-pass")
                client.awaitChat { "logged in" in it }
            }
        }
    }

    @Test
    fun `names differing only in case are refused`() {
        TestServer().use { test ->
            test.server.auth.register("Alex", "secret-pass").get()
            test.client(MINECRAFT_1_20_3, "alex").use { client ->
                assertContains(client.connect().awaitDisconnect(), "Alex")
            }
        }
    }

    @Test
    fun `too many wrong passwords kick`() {
        TestServer(configure = { it.copy(authentication = it.authentication.copy(maxLoginAttempts = 2)) }).use { test ->
            test.server.auth.register("Bob", "secret-pass").get()
            test.client(MINECRAFT_1_12_2, "Bob").use { client ->
                client.connect()
                client.command("login nope-nope")
                client.command("login nope-nope")
                assertContains(client.awaitDisconnect(), "Too many")
            }
        }
    }

    @Test
    fun `players who do not log in are timed out`() {
        TestServer(configure = { it.copy(authentication = it.authentication.copy(loginTimeoutSeconds = 2)) }).use { test ->
            test.client(MINECRAFT_26_2, "Slow").use { client ->
                assertContains(client.connect().awaitDisconnect(), "in time")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(names = ["MINECRAFT_1_8", "MINECRAFT_1_20_3", "MINECRAFT_26_3"])
    fun `licensed names log in through mojang`(version: ProtocolVersion) {
        val uuid = UUID.randomUUID()
        TestServer(FakeMojang(mapOf("Notch" to uuid))).use { test ->
            test.client(version, "Notch").use { client ->
                client.connect()
                assertTrue(client.encrypted)
                assertEquals(uuid, client.loginSuccess?.uuid)
                client.awaitChat { "Licensed account verified" in it }
            }
            val account = assertNotNull(test.server.auth.account("notch").get())
            assertTrue(account.premium)
            assertEquals(uuid, account.premiumUuid)
        }
    }

    @Test
    fun `premium policy online refuses unlicensed names`() {
        TestServer(configure = { it.copy(authentication = it.authentication.copy(premiumPolicy = PremiumPolicy.ONLINE)) }).use { test ->
            test.client(MINECRAFT_1_20_3, "Cracked").use { client ->
                assertContains(client.connect().awaitDisconnect(), "licensed")
            }
        }
    }
}

internal fun waitUntil(timeoutMillis: Long = 5000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Condition not met in time" }
        Thread.sleep(20)
    }
}
