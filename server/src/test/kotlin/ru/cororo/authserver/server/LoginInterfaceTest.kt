package ru.cororo.authserver.server

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_3
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_21_4
import ru.cororo.authserver.protocol.buffer.ItemStack
import ru.cororo.authserver.server.config.PremiumPolicy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

class LoginInterfaceTest {
    private fun ItemStack.plainName() = name?.let(PlainTextComponentSerializer.plainText()::serialize)

    private fun itemId(version: ProtocolVersion, type: String) = GameData.version(version).item(GameData.item(type)!!)

    @ParameterizedTest
    @EnumSource(names = ["MINECRAFT_1_8", "MINECRAFT_1_12_2", "MINECRAFT_1_20_3", "MINECRAFT_1_21_4", "MINECRAFT_26_3"])
    fun `new players get the help item and pick a password in the menu`(version: ProtocolVersion) {
        TestServer().use { test ->
            test.client(version, "Steve").use { client ->
                client.connect()
                client.await { client.windows[0]?.get(36) != null && client.screen != null }
                val compass = assertNotNull(client.windows.getValue(0)[36])
                assertEquals(itemId(version, "minecraft:compass"), compass.id)
                assertEquals("How to log in", compass.plainName())
                val screen = assertNotNull(client.screen)
                assertEquals(3, screen.rows)
                client.await { client.windows[screen.windowId]?.get(15) != null }
                assertEquals(itemId(version, "minecraft:emerald"), client.windows.getValue(screen.windowId)[15]?.id)

                client.click(screen.windowId, 11)
                client.awaitChat { "This name is new" in it }
                client.await { client.screen == null }

                client.useItem()
                client.await { client.screen != null }
                client.closeScreen(client.screen!!.windowId)
                client.command("register secret-pass secret-pass")
                client.awaitChat { "registered" in it }
                client.await { client.windows[0]?.all { it == null } == true }
            }
        }
    }

    /** Picks the licensed login in the join menu. */
    private fun chooseLicensed(client: TestClient) {
        client.await { client.screen != null }
        val window = client.screen!!.windowId
        client.click(window, 15)
        client.await { client.windows[window]?.get(13) != null }
        assertEquals("Confirm: I own a licensed account", client.windows.getValue(window)[13]!!.plainName())
        client.click(window, 13)
    }

    @Test
    fun `a licensed choice is confirmed and the next connection logs in through mojang`() {
        val uuid = UUID.randomUUID()
        TestServer(FakeMojang(mapOf("Buyer" to uuid))).use { test ->
            test.client(MINECRAFT_1_20_3, "Buyer").use { client ->
                client.connect()
                assertFalse(client.encrypted)
                chooseLicensed(client)
                assertContains(client.awaitDisconnect(), "Reconnect with your licensed")
            }
            assertNull(test.server.auth.account("Buyer").get(), "Nothing is stored before Mojang confirmed the player")
            test.client(MINECRAFT_1_20_3, "Buyer").use { client ->
                client.connect()
                assertTrue(client.encrypted)
                assertEquals(uuid, client.loginSuccess?.uuid)
                client.awaitChat { "Licensed account verified" in it }
            }
            val account = assertNotNull(test.server.auth.account("Buyer").get())
            assertTrue(account.premium)
            assertEquals(uuid, account.premiumUuid)
            assertEquals(false, account.hasPassword)
        }
    }

    @Test
    fun `a failed licensed login returns to the choice`() {
        TestServer().use { test ->
            test.client(MINECRAFT_1_20_3, "Pirate").use { client ->
                chooseLicensed(client.connect())
                client.awaitDisconnect()
            }
            test.client(MINECRAFT_1_20_3, "Pirate").use { client ->
                assertContains(client.connect().awaitDisconnect(), "Mojang did not confirm")
                assertTrue(client.encrypted)
            }
            test.client(MINECRAFT_1_20_3, "Pirate").use { client ->
                client.connect()
                assertFalse(client.encrypted, "Back to an offline login")
                client.awaitChat { "licensed login did not work" in it }
                client.await { client.screen != null }
                client.command("register secret-pass secret-pass")
                client.awaitChat { "registered" in it }
            }
            assertEquals(false, test.server.auth.account("Pirate").get()?.premium)
        }
    }

    @Test
    fun `new clients are transferred back instead of reconnecting`() {
        TestServer().use { test ->
            test.client(MINECRAFT_1_21_4, "Buyer").use { client ->
                chooseLicensed(client.connect("play.example.net"))
                client.await { client.transfer != null }
                assertEquals("play.example.net", client.transfer?.host)
                assertEquals(25565, client.transfer?.port)
            }
        }
    }

    @Test
    fun `the premium command offers the licensed login to new players`() {
        TestServer(configure = { it.copy(authentication = it.authentication.copy(loginMenu = false)) }).use { test ->
            test.client(MINECRAFT_1_20_3, "Buyer").use { client ->
                client.connect()
                client.awaitChat { "/register" in it }
                assertNull(client.screen, "login-menu: false does not open the menu on join")
                client.command("premium")
                client.awaitChat { "Only do this if you own a licensed" in it }
                client.command("premium confirm")
                assertContains(client.awaitDisconnect(), "Reconnect with your licensed")
            }
        }
    }

    @Test
    fun `the premium command cannot switch an account before logging in`() {
        TestServer().use { test ->
            test.server.auth.register("Alex", "secret-pass").get()
            test.client(MINECRAFT_1_20_3, "Alex").use { client ->
                client.connect()
                client.command("premium")
                client.awaitChat { "Log in first" in it }
            }
            assertEquals(false, test.server.auth.account("Alex").get()?.premium)
        }
    }

    @ParameterizedTest
    @EnumSource(PremiumPolicy::class, names = ["AUTO", "OFFLINE"])
    fun `only the manual policy offers the choice`(policy: PremiumPolicy) {
        TestServer(configure = { it.copy(authentication = it.authentication.copy(premiumPolicy = policy)) }).use { test ->
            test.client(MINECRAFT_1_20_3, "Solo").use { client ->
                client.connect()
                client.await { client.windows[0]?.get(36) != null }
                client.useItem()
                client.awaitChat { "This name is new" in it }
                assertNull(client.screen)
            }
        }
    }

    @Test
    fun `accounts keep their kind of login`() {
        val uuid = UUID.randomUUID()
        TestServer(FakeMojang(mapOf("Notch" to uuid, "Alex" to UUID.randomUUID()))).use { test ->
            test.server.auth.register("Alex", "secret-pass").get()
            test.client(MINECRAFT_1_20_3, "Alex").use { client ->
                client.connect()
                assertFalse(client.encrypted, "A password account never logs in through Mojang")
                client.awaitChat { "/login" in it }
            }
            test.client(MINECRAFT_1_20_3, "Notch").use { client ->
                chooseLicensed(client.connect())
                client.awaitDisconnect()
            }
            test.client(MINECRAFT_1_20_3, "Notch").use { client ->
                client.connect()
                client.awaitChat { "Licensed account verified" in it }
            }
            test.client(MINECRAFT_1_20_3, "Notch").use { client ->
                client.connect()
                assertTrue(client.encrypted, "A licensed account always logs in through Mojang")
                client.awaitChat { "Licensed account verified" in it }
            }
        }
    }

    @Test
    fun `registered players get instructions instead of the menu`() {
        TestServer().use { test ->
            test.server.auth.register("Alex", "secret-pass").get()
            test.client(MINECRAFT_1_20_3, "Alex").use { client ->
                client.connect()
                client.await { client.windows[0]?.get(36) != null }
                client.useItem()
                client.awaitChat { "This name is registered" in it }
                assertNull(client.screen, "No menu for existing accounts")
            }
        }
    }

    @Test
    fun `the menu is not offered without licensed login`() {
        TestServer(configure = { it.copy(authentication = it.authentication.copy(licensedLogin = false)) }).use { test ->
            test.client(MINECRAFT_1_20_3, "Solo").use { client ->
                client.connect()
                client.await { client.windows[0]?.get(36) != null }
                client.useItem()
                client.awaitChat { "This name is new" in it }
                assertNull(client.screen)
            }
        }
    }
}
