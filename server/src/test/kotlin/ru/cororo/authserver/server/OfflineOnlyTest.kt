package ru.cororo.authserver.server

import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_12_2
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OfflineOnlyTest {
    @Test
    fun `licensed names register with a password when licensed login is disabled`() {
        TestServer(FakeMojang(mapOf("Notch" to UUID.randomUUID())), configure = {
            it.copy(authentication = it.authentication.copy(licensedLogin = false))
        }).use { test ->
            test.client(MINECRAFT_1_12_2, "Notch").use { client ->
                client.connect()
                assertFalse(client.encrypted, "No Mojang authentication")
                client.command("register secret-pass secret-pass")
                client.awaitChat { "registered" in it }
            }
            assertNull(test.server.commands.command("premium"))
            assertFalse(test.server.auth.account("Notch").get()!!.premium)
        }
    }
}
