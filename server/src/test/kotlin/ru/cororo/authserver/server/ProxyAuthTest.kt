package ru.cororo.authserver.server

import kotlin.test.assertNull
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_21_4
import kotlinx.coroutines.runBlocking
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.netty.buffer.Unpooled
import ru.cororo.authserver.api.event.PlayerAuthenticatedEvent
import ru.cororo.authserver.api.event.on
import ru.cororo.authserver.bridge.AuthMethod
import ru.cororo.authserver.bridge.AuthMode
import ru.cororo.authserver.bridge.BridgeCodec
import ru.cororo.authserver.bridge.BridgeMessage
import ru.cororo.authserver.bridge.PlayerStatus
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_12_2
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_3
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_26_3
import ru.cororo.authserver.protocol.buffer.writeString
import ru.cororo.authserver.protocol.buffer.writeUuid
import ru.cororo.authserver.protocol.buffer.writeVarInt
import ru.cororo.authserver.server.auth.offlineUuid
import ru.cororo.authserver.server.config.ApiConfig
import ru.cororo.authserver.server.config.ForwardingMode
import ru.cororo.authserver.server.config.PremiumPolicy
import ru.cororo.authserver.server.config.ProxyConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProxyAuthTest {
    private val secret = "proxy-secret-0123456789"
    private val bridge = BridgeCodec(secret)

    private fun velocityServer(configure: (TestServer) -> Unit = {}) = TestServer { config ->
        config.copy(proxy = ProxyConfig(ForwardingMode.VELOCITY, secret), api = ApiConfig(enabled = true, port = 0))
    }.also(configure)

    /** What Velocity answers to `velocity:player_info` (modern forwarding version 1). */
    private fun forwarding(uuid: UUID, name: String, key: String = secret): ByteArray {
        val payload = Unpooled.buffer().apply {
            writeVarInt(1)
            writeString("203.0.113.7")
            writeUuid(uuid)
            writeString(name)
            writeVarInt(0)
        }.let { ByteArray(it.readableBytes()).also(it::readBytes) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }.doFinal(payload)
        return mac + payload
    }

    @Test
    fun `offline player behind velocity registers and the proxy is told`() {
        velocityServer().use { test ->
            test.client(MINECRAFT_1_20_3, "Steve").use { client ->
                client.onLoginQuery = { forwarding(offlineUuid("Steve"), "Steve") }
                client.connect()
                assertEquals(offlineUuid("Steve"), client.loginSuccess?.uuid)
                client.command("register secret-pass secret-pass")
                val message = assertIs<BridgeMessage.Authenticated>(client.awaitBridge(bridge))
                assertEquals(AuthMode.OFFLINE, message.mode)
                assertEquals(AuthMethod.REGISTER, message.method)
                assertEquals("Steve", message.username)
            }
            assertEquals("203.0.113.7", test.server.auth.account("Steve").get()?.lastLoginIp, "Forwarded address is used")
        }
    }

    @Test
    fun `licensed player behind velocity is authenticated at once and plugins can pick the server`() {
        velocityServer().use { test ->
            test.server.events.on<PlayerAuthenticatedEvent>(test.server.core) { it.targetServer = "survival" }
            val uuid = UUID.randomUUID()
            test.client(MINECRAFT_26_3, "Notch").use { client ->
                client.onLoginQuery = { forwarding(uuid, "Notch") }
                client.connect()
                val message = assertIs<BridgeMessage.Authenticated>(client.awaitBridge(bridge))
                assertEquals(AuthMode.ONLINE, message.mode)
                assertEquals(AuthMethod.PREMIUM, message.method)
                assertEquals("survival", message.target.orElse(null))
            }
        }
    }

    @Test
    fun `a licensed choice behind velocity asks the proxy to reconnect the player through mojang`() {
        velocityServer().use { test ->
            test.client(MINECRAFT_1_21_4, "Buyer").use { client ->
                client.onLoginQuery = { forwarding(offlineUuid("Buyer"), "Buyer") }
                client.connect()
                client.await { client.screen != null }
                val window = client.screen!!.windowId
                client.click(window, 15)
                client.await { client.windows[window]?.get(13) != null }
                client.click(window, 13)
                val message = assertIs<BridgeMessage.LicensedLogin>(client.awaitBridge(bridge))
                assertEquals("Buyer", message.username)
                assertContains(message.detail, "Reconnect")
                assertNull(client.transfer, "Behind a proxy the plugin transfers the player")
            }
            // The next pre-login goes online, once.
            assertTrue(runBlocking { test.server.premium.status("Buyer") }.onlineMode)
            assertEquals(false, runBlocking { test.server.premium.status("Buyer") }.onlineMode)
        }
    }

    @Test
    fun `forged velocity data is rejected`() {
        velocityServer().use { test ->
            test.client(MINECRAFT_1_20_3, "Mallory").use { client ->
                client.onLoginQuery = { forwarding(UUID.randomUUID(), "Mallory", key = "not-the-real-secret-at-all") }
                assertContains(client.connect().awaitDisconnect(), "proxy")
            }
        }
    }

    @Test
    fun `bungeeguard forwarding needs the token`() {
        TestServer { it.copy(proxy = ProxyConfig(ForwardingMode.LEGACY, secret)) }.use { test ->
            fun address(token: String) = "localhost\u0000198.51.100.2\u0000${offlineUuid("Legacy").toString().replace("-", "")}\u0000" +
                Gson().toJson(JsonArray().apply { add(JsonObject().apply { addProperty("name", "bungeeguard-token"); addProperty("value", token) }) })
            test.client(MINECRAFT_1_12_2, "Legacy").use { client ->
                assertContains(client.connect(address("wrong-token")).awaitDisconnect(), "proxy")
            }
            test.client(MINECRAFT_1_12_2, "Legacy").use { client ->
                client.connect(address(secret))
                client.awaitChat { "/register" in it }
            }
        }
    }

    @Test
    fun `http api reports the login mode`() {
        TestServer(FakeMojang(mapOf("Notch" to UUID.randomUUID()))) { config ->
            config.copy(
                proxy = ProxyConfig(ForwardingMode.VELOCITY, secret), api = ApiConfig(enabled = true, port = freePort()),
                authentication = config.authentication.copy(premiumPolicy = PremiumPolicy.AUTO),
            )
        }.use { test ->
            test.server.auth.register("Steve", "secret-pass").get()
            val http = HttpClient.newHttpClient()
            fun status(name: String, key: String = secret): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${test.config.api.port}${PlayerStatus.PATH}$name"))
                    .header(PlayerStatus.SECRET_HEADER, key).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, status("Steve", key = "wrong-secret-value-123").statusCode())
            val steve = Gson().fromJson(status("steve").body(), PlayerStatus::class.java)
            assertTrue(steve.registered)
            assertEquals(false, steve.onlineMode)
            assertEquals("Steve", steve.username)
            assertTrue(Gson().fromJson(status("Notch").body(), PlayerStatus::class.java).onlineMode, "Licensed names go online")
            assertEquals(false, Gson().fromJson(status("Nobody").body(), PlayerStatus::class.java).onlineMode)
        }
    }

    private fun freePort() = java.net.ServerSocket(0).use { it.localPort }
}
