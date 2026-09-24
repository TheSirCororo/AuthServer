package ru.cororo.authserver.server.auth

import com.google.gson.JsonParser
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import ru.cororo.authserver.protocol.packet.ProfileProperty
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GameProfile(val uuid: UUID, val name: String, val properties: List<ProfileProperty> = emptyList())

/** Mojang session and profile services; overridable in tests. */
interface MojangApi {
    /** Profile of a player who joined with [serverHash], or `null` if Mojang did not confirm the login. */
    suspend fun hasJoined(username: String, serverHash: String): GameProfile?

    /** Whether a licensed account named [username] exists; `null` when Mojang could not be asked. */
    suspend fun accountExists(username: String): Boolean?
}

class HttpMojangApi(
    private val sessionServer: String,
    private val profileApi: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build(),
) : MojangApi {
    private val logger = LoggerFactory.getLogger(HttpMojangApi::class.java)
    private val existence = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    override suspend fun hasJoined(username: String, serverHash: String): GameProfile? {
        val uri = URI.create("$sessionServer/session/minecraft/hasJoined?username=${encode(username)}&serverId=${encode(serverHash)}")
        val response = http.sendAsync(request(uri), HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() == 204 || response.statusCode() == 403) return null
        check(response.statusCode() == 200) { "Session server answered ${response.statusCode()}" }
        val json = JsonParser.parseString(response.body()).asJsonObject
        val name = json["name"].asString
        require(name.equals(username, ignoreCase = true)) { "Session server returned profile $name for $username" }
        val properties = json.getAsJsonArray("properties")?.map { element ->
            val property = element.asJsonObject
            ProfileProperty(property["name"].asString, property["value"].asString, property["signature"]?.asString)
        }.orEmpty()
        return GameProfile(parseUuid(json["id"].asString), name, properties)
    }

    override suspend fun accountExists(username: String): Boolean? {
        val key = username.lowercase()
        existence[key]?.let { (exists, at) -> if (System.currentTimeMillis() - at < CACHE_MILLIS) return exists }
        return try {
            val response = http.sendAsync(request(URI.create(profileApi + encode(username))), HttpResponse.BodyHandlers.discarding()).await()
            when (response.statusCode()) {
                200 -> true
                204, 404 -> false
                else -> null.also { logger.warn("Profile API answered {} for {}", response.statusCode(), username) }
            }?.also { existence[key] = it to System.currentTimeMillis() }
        } catch (exception: Exception) {
            logger.warn("Could not look up {} at Mojang: {}", username, exception.toString())
            null
        }
    }

    private fun request(uri: URI) = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build()

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val CACHE_MILLIS = 60 * 60 * 1000L
    }
}

/** Mojang UUIDs come without dashes. */
fun parseUuid(value: String): UUID =
    if ('-' in value) UUID.fromString(value)
    else UUID(java.lang.Long.parseUnsignedLong(value.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(value.substring(16), 16))

/** UUID vanilla assigns to offline players. */
fun offlineUuid(username: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))
