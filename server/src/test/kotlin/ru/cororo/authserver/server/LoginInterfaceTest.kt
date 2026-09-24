package ru.cororo.authserver.server

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.cororo.authserver.gamedata.GameData
import ru.cororo.authserver.protocol.ProtocolVersion
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_3
import ru.cororo.authserver.protocol.buffer.ItemStack
import ru.cororo.authserver.server.config.PremiumPolicy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `choosing a licensed login needs confirmation and binds the name`() {
        TestServer().use { test ->
            test.client(MINECRAFT_1_20_3, "Buyer").use { client ->
                client.connect()
                client.await { client.screen != null }
                val window = client.screen!!.windowId
                client.click(window, 15)
                client.await { client.windows[window]?.get(13) != null }
                assertEquals("Confirm: I own a licensed account", client.windows.getValue(window)[13]!!.plainName())
                client.click(window, 13)
                assertContains(client.awaitDisconnect(), "Reconnect with your licensed")
            }
            val account = assertNotNull(test.server.auth.account("Buyer").get())
            assertTrue(account.premium)
            assertEquals(false, account.hasPassword)
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
    fun `the menu is not offered when only one login kind is possible`() {
        TestServer(configure = { it.copy(authentication = it.authentication.copy(premiumPolicy = PremiumPolicy.OFFLINE, licensedLogin = false)) }).use { test ->
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
