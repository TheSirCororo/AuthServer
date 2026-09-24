package ru.cororo.authserver.server

import com.google.gson.Gson
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.cororo.authserver.bridge.AccountApi
import ru.cororo.authserver.bridge.PlayerStatus
import ru.cororo.authserver.protocol.ProtocolVersion.MINECRAFT_1_20_3
import ru.cororo.authserver.server.auth.security.Totp
import ru.cororo.authserver.server.config.ApiConfig
import ru.cororo.authserver.server.config.EmailConfig
import ru.cororo.authserver.server.config.ForwardingMode
import ru.cororo.authserver.server.config.ProxyConfig
import ru.cororo.authserver.server.config.ServerConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountSecurityTest {
    private val address = "steve@example.com"

    private fun withEmail(config: ServerConfig) = config.copy(email = EmailConfig(
        enabled = true, host = "smtp.invalid", from = "server@example.com", resendSeconds = 0,
    ))

    private fun TestServer.join(name: String = "Steve") = client(MINECRAFT_1_20_3, name).connect()

    /** Registers Steve and links [address] through the email confirmation. */
    private fun TestServer.registerWithEmail(): TestClient {
        val client = join()
        client.command("register secret-pass secret-pass")
        client.awaitChat { "registered" in it }
        client.command("email set $address secret-pass")
        client.awaitChat { "/email confirm" in it }
        client.command("email confirm ${mail.code(address)}")
        client.awaitChat { "linked" in it }
        return client
    }

    @Test
    fun `an authenticator app protects the login and a code works once`() {
        TestServer().use { test ->
            val secret = test.join().use { client ->
                client.command("register secret-pass secret-pass")
                client.awaitChat { "registered" in it }
                client.command("2fa totp")
                val key = client.awaitChat { it.matches(Regex("[A-Z2-7]{4}( [A-Z2-7]{4})+")) }.replace(" ", "")
                client.command("2fa confirm ${Totp.code(key, Totp.step(Instant.now()))}")
                client.awaitChat { "Two-factor authentication is on" in it }
                key
            }
            assertTrue(test.server.auth.account("Steve").get()!!.twoFactor)
            test.join().use { client ->
                client.command("login secret-pass")
                client.awaitChat { "authenticator app" in it }
                client.command("code 000000")
                client.awaitChat { "Wrong code" in it }
                // The confirmation used the current step; the next one is within the allowed drift. Typed as the app shows it.
                val next = Totp.code(secret, Totp.step(Instant.now()) + 1)
                client.command("code ${next.chunked(3).joinToString(" ")}")
                client.awaitChat { "You are logged in" in it }
            }
            test.join().use { client ->
                client.command("login secret-pass")
                client.awaitChat { "authenticator app" in it }
                client.command("code ${Totp.code(secret, Totp.step(Instant.now()) + 1)}")
                client.awaitChat { "Wrong code" in it }
                assertTrue(client.chat.none { "You are logged in" in it }, "A used code is rejected")
            }
        }
    }

    @Test
    fun `guessing authenticator codes locks the account across connections`() {
        TestServer().use { test ->
            test.join().use { client ->
                client.command("register secret-pass secret-pass")
                client.awaitChat { "registered" in it }
                client.command("2fa totp")
                val key = client.awaitChat { it.matches(Regex("[A-Z2-7]{4}( [A-Z2-7]{4})+")) }.replace(" ", "")
                client.command("2fa confirm ${Totp.code(key, Totp.step(Instant.now()))}")
                client.awaitChat { "Two-factor authentication is on" in it }
            }
            test.join().use { client ->
                client.command("login secret-pass")
                client.awaitChat { "authenticator app" in it }
                repeat(4) { client.command("code 00000$it") }
                client.await { client.chat.count { "Wrong code" in it } == 4 }
            }
            test.join().use { client ->
                client.command("login secret-pass")
                client.awaitChat { "authenticator app" in it }
                client.command("code 000009")
                client.awaitChat { "Wrong code" in it }
                client.command("code 000008")
                client.awaitChat { "Too many wrong passwords or codes" in it }
            }
        }
    }

    @Test
    fun `email codes link the address and protect the login`() {
        TestServer(configure = ::withEmail).use { test ->
            test.registerWithEmail().use { client ->
                assertEquals(address, test.server.auth.account("Steve").get()!!.email)
                client.command("2fa email")
                client.awaitChat { "Confirm with /2fa confirm" in it }
                client.command("2fa confirm ${test.mail.code(address)}")
                client.awaitChat { "Two-factor authentication is on" in it }
                client.command("email remove secret-pass")
                client.awaitChat { "receives your login codes" in it }
            }
            test.join().use { client ->
                client.command("login secret-pass")
                client.awaitChat { "Enter the code sent to" in it }
                assertContains(test.mail.sent.last().subject, "login code")
                assertTrue(client.chat.none { address in it }, "The address is masked: ${client.chat}")
                client.command("code ${test.mail.code(address)}")
                client.awaitChat { "You are logged in" in it }
            }
        }
    }

    @Test
    fun `a forgotten password is recovered by email`() {
        TestServer(configure = ::withEmail).use { test ->
            test.registerWithEmail().close()
            test.join().use { client ->
                client.command("recover")
                client.awaitChat { "Set a new password" in it }
                client.command("recover 000000 new-secret new-secret")
                client.awaitChat { "Wrong code" in it }
                client.command("recover ${test.mail.code(address)} new-secret new-secret")
                client.awaitChat { "Your password was changed" in it }
                client.awaitChat { "You are logged in" in it }
            }
            test.join().use { client ->
                client.command("login new-secret")
                client.awaitChat { "You are logged in" in it }
            }
        }
    }

    @Test
    fun `recovery does not skip an authenticator app`() {
        TestServer(configure = ::withEmail).use { test ->
            test.registerWithEmail().use { client ->
                client.command("2fa totp")
                val key = client.awaitChat { it.matches(Regex("[A-Z2-7]{4}( [A-Z2-7]{4})+")) }.replace(" ", "")
                client.command("2fa confirm ${Totp.code(key, Totp.step(Instant.now()))}")
                client.awaitChat { "Two-factor authentication is on" in it }
            }
            test.join().use { client ->
                client.command("recover")
                client.awaitChat { "Set a new password" in it }
                client.command("recover ${test.mail.code(address)} new-secret new-secret")
                client.awaitChat { "authenticator app" in it }
                assertTrue(client.chat.none { "You are logged in" in it })
            }
        }
    }

    @Test
    fun `guessing the password in account commands locks them`() {
        TestServer(configure = ::withEmail).use { test ->
            test.join().use { client ->
                client.command("register secret-pass secret-pass")
                client.awaitChat { "registered" in it }
                repeat(5) { client.command("email set $address wrong-pass-$it") }
                client.await { client.chat.count { "Wrong password" in it } == 5 }
                // Locked now, even with the right password.
                client.command("email set $address secret-pass")
                client.awaitChat { "Too many wrong passwords" in it }
                assertTrue(test.mail.sent.isEmpty())
            }
        }
    }

    @Test
    fun `turning email two-factor off needs a code`() {
        TestServer(configure = ::withEmail).use { test ->
            test.registerWithEmail().use { client ->
                client.command("2fa email")
                client.awaitChat { "Confirm with /2fa confirm" in it }
                client.command("2fa confirm ${test.mail.code(address)}")
                client.awaitChat { "Two-factor authentication is on" in it }
                client.command("2fa off 123456")
                client.awaitChat { "no valid code" in it.lowercase() }
                client.command("2fa off")
                client.awaitChat { "/2fa off <code>" in it }
                client.command("2fa off ${test.mail.code(address)}")
                client.awaitChat { "Two-factor authentication is off" in it }
            }
            assertEquals(false, test.server.auth.account("Steve").get()!!.twoFactor)
        }
    }

    @Test
    fun `the proxy runs account commands through the api`() {
        val secret = "proxy-secret-0123456789"
        val port = java.net.ServerSocket(0).use { it.localPort }
        TestServer(configure = { withEmail(it).copy(proxy = ProxyConfig(ForwardingMode.VELOCITY, secret), api = ApiConfig(enabled = true, port = port)) }).use { test ->
            test.server.auth.register("Steve", "secret-pass").get()
            fun run(command: String, vararg arguments: String): List<String> {
                val response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${PlayerStatus.PATH}Steve${AccountApi.COMMAND}"))
                        .header(PlayerStatus.SECRET_HEADER, secret)
                        .POST(HttpRequest.BodyPublishers.ofString(Gson().toJson(AccountApi.AccountCommand(command, arguments.toList(), "ru-RU"))))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, response.statusCode(), response.body())
                return Gson().fromJson(response.body(), AccountApi.AccountCommandResponse::class.java).messages()
                    .map { PlainTextComponentSerializer.plainText().serialize(GsonComponentSerializer.gson().deserialize(it)) }
            }
            assertContains(run("2fa").single(), "выключена", message = "Answers use the player's language")
            run("email", "set", address, "secret-pass")
            run("email", "confirm", test.mail.code(address))
            assertEquals(address, test.server.auth.account("Steve").get()!!.email)
            assertNull(test.server.auth.account("Nobody").get())
        }
    }
}
