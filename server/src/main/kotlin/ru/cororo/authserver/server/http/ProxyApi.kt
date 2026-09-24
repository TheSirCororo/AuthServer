package ru.cororo.authserver.server.http

import java.util.Locale
import ru.cororo.authserver.server.auth.security.SecurityActor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.Component
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import ru.cororo.authserver.bridge.AccountApi
import ru.cororo.authserver.bridge.PlayerStatus
import ru.cororo.authserver.server.AuthServerImpl
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * HTTP API for the proxy plugin, authenticated by the shared secret header:
 * - `GET  /api/v1/players/{name}` - how the name authenticates ([PlayerStatus]);
 * - `POST /api/v1/players/{name}/password` - change the password ([AccountApi.PasswordChange]);
 * - `POST /api/v1/players/{name}/logout` - end the session;
 * - `POST /api/v1/players/{name}/command` - run `/email` or `/2fa` for a player on another server
 *   ([AccountApi.AccountCommand]).
 *
 * Runs on the JDK's built-in HTTP server with virtual threads, so it needs no extra dependency.
 */
class ProxyApi(private val server: AuthServerImpl) {
    private val logger = LoggerFactory.getLogger(ProxyApi::class.java)
    private val gson = Gson()
    private val secret = server.config.proxy.secret.toByteArray()
    private var http: HttpServer? = null

    fun start(host: String, port: Int) {
        val created = try {
            HttpServer.create(InetSocketAddress(host, port), 0)
        } catch (exception: java.net.BindException) {
            throw IllegalStateException("Cannot listen on $host:$port for the HTTP API: ${exception.message}", exception)
        }
        http = created.apply {
            executor = Executors.newVirtualThreadPerTaskExecutor()
            createContext(PlayerStatus.PATH, ::handle)
            start()
        }
        logger.info("HTTP API listening on {}:{}", host, port)
    }

    private fun handle(exchange: HttpExchange) = exchange.use {
        try {
            val provided = exchange.requestHeaders.getFirst(PlayerStatus.SECRET_HEADER)?.toByteArray()
            if (provided == null || !MessageDigest.isEqual(provided, secret)) return@use exchange.respond(401)
            val path = URLDecoder.decode(exchange.requestURI.rawPath.removePrefix(PlayerStatus.PATH), Charsets.UTF_8)
            val name = path.substringBefore('/')
            if (!server.auth.isValidName(name)) return@use exchange.respond(400)
            when ("${exchange.requestMethod} ${path.removePrefix(name)}") {
                "GET " -> exchange.respond(200, gson.toJson(runBlocking { server.premium.status(name) }))
                "POST ${AccountApi.PASSWORD}" -> changePassword(exchange, name)
                "POST ${AccountApi.COMMAND}" -> accountCommand(exchange, name)
                "POST ${AccountApi.LOGOUT}" -> {
                    runBlocking { server.auth.logout(name) }
                    exchange.respond(204)
                }
                else -> exchange.respond(404)
            }
        } catch (exception: Exception) {
            logger.error("HTTP API request failed", exception)
            exchange.respond(500)
        }
    }

    private fun changePassword(exchange: HttpExchange, name: String) {
        val request = try {
            gson.fromJson(exchange.requestBody.bufferedReader().readText(), AccountApi.PasswordChange::class.java)
        } catch (_: JsonParseException) {
            null
        }
        if (request?.oldPassword() == null || request.newPassword() == null) return exchange.respond(400)
        val result = runBlocking { server.auth.changePassword(name, request.oldPassword(), request.newPassword()) }
        val limits = server.auth.passwordLimits
        exchange.respond(200, gson.toJson(AccountApi.PasswordChangeResponse(result, limits.first, limits.last)))
    }

    /** The proxy vouches that the player is logged in; the answers go back as JSON components. */
    private fun accountCommand(exchange: HttpExchange, name: String) {
        val request = try {
            gson.fromJson(exchange.requestBody.bufferedReader().readText(), AccountApi.AccountCommand::class.java)
        } catch (_: JsonParseException) {
            null
        }
        if (request?.command() == null || request.arguments() == null || request.command() !in AccountApi.COMMANDS) return exchange.respond(400)
        val replies = mutableListOf<String>()
        val actor = object : SecurityActor {
            override val username = name
            override val locale: Locale = Locale.forLanguageTag(request.locale()?.replace('_', '-') ?: "en")
            override fun reply(message: Component) {
                replies += GsonComponentSerializer.gson().serialize(message)
            }
        }
        runBlocking {
            when (request.command()) {
                "email" -> server.security.emailCommand(actor, request.arguments())
                "2fa" -> server.security.twoFactorCommand(actor, request.arguments())
            }
        }
        exchange.respond(200, gson.toJson(AccountApi.AccountCommandResponse(replies)))
    }

    private fun HttpExchange.respond(status: Int, body: String = "") {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
    }

    fun stop() {
        http?.stop(0)
    }
}
