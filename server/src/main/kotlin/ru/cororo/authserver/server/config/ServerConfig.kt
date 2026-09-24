package ru.cororo.authserver.server.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlNamingStrategy
import kotlinx.serialization.Serializable
import ru.cororo.authserver.storage.DatabaseType
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ServerConfig(
    val network: NetworkConfig = NetworkConfig(),
    val proxy: ProxyConfig = ProxyConfig(),
    val authentication: AuthenticationConfig = AuthenticationConfig(),
    val database: DatabaseSection = DatabaseSection(),
    val world: WorldConfig = WorldConfig(),
    val status: StatusConfig = StatusConfig(),
    val messages: MessagesConfig = MessagesConfig(),
    val api: ApiConfig = ApiConfig(),
    val transfer: TransferConfig = TransferConfig(),
    val email: EmailConfig = EmailConfig(),
    val twoFactor: TwoFactorConfig = TwoFactorConfig(),
) {
    fun validate() {
        require(network.port in 1..65535) { "network.port must be 1-65535" }
        require(network.compressionThreshold >= -1) { "network.compression-threshold must be -1 or more" }
        val unprotectedLegacy = proxy.forwarding == ForwardingMode.LEGACY && proxy.allowUnprotectedLegacyForwarding && proxy.secret.isEmpty()
        if (proxy.forwarding != ForwardingMode.NONE && !unprotectedLegacy) {
            require(proxy.secret.length >= 16) { "proxy.secret must be at least 16 characters when forwarding is enabled" }
        }
        if (api.enabled) require(proxy.secret.length >= 16) { "api.enabled needs proxy.secret (at least 16 characters)" }
        require(authentication.licensedLogin || authentication.premiumPolicy != PremiumPolicy.ONLINE) {
            "authentication.premium-policy ONLINE needs licensed-login: true"
        }
        require(authentication.maxLoginAttempts >= 1) { "authentication.max-login-attempts must be positive" }
        require(authentication.minPasswordLength in 1..authentication.maxPasswordLength) {
            "authentication.min-password-length must be between 1 and max-password-length"
        }
        require(runCatching { Regex(authentication.usernamePattern) }.isSuccess) { "authentication.username-pattern is not a valid regex" }
        if (email.enabled) {
            require(email.host.isNotBlank()) { "email.host is required when email is enabled" }
            require(email.port in 1..65535) { "email.port must be 1-65535" }
            require('@' in email.from) { "email.from must be an email address" }
        }
        require(email.codeMinutes >= 1 && email.resendSeconds >= 0 && email.maxCodeAttempts >= 1) {
            "email.code-minutes and email.max-code-attempts must be positive"
        }
        require(twoFactor.totpWindow in 0..5) { "two-factor.totp-window must be 0-5" }
    }

    companion object {
        private val yaml = Yaml(
            configuration = YamlConfiguration(
                strictMode = false,
                yamlNamingStrategy = YamlNamingStrategy.KebabCase,
                decodeEnumCaseInsensitive = true,
            ),
        )

        /** Loads [path], writing the documented default file first if it does not exist. */
        fun load(path: Path): ServerConfig {
            if (Files.notExists(path)) {
                path.toAbsolutePath().parent?.let(Files::createDirectories)
                ServerConfig::class.java.getResourceAsStream("/config.yml")!!.use { Files.copy(it, path) }
            }
            return yaml.decodeFromString(serializer(), Files.readString(path)).also(ServerConfig::validate)
        }
    }
}

@Serializable
data class NetworkConfig(
    val host: String = "0.0.0.0",
    val port: Int = 25565,
    /** Packets at least this large are compressed; -1 disables compression. */
    val compressionThreshold: Int = 256,
    val readTimeoutSeconds: Int = 30,
)

enum class ForwardingMode {
    /** Clients connect directly; the auth server authenticates licensed players itself. */
    NONE,
    /** Velocity modern forwarding (1.13+ clients). */
    VELOCITY,
    /** BungeeCord / Velocity legacy forwarding, optionally protected by a BungeeGuard token. */
    LEGACY,
}

@Serializable
data class ProxyConfig(
    val forwarding: ForwardingMode = ForwardingMode.NONE,
    /** Velocity forwarding secret, BungeeGuard token and bridge signing key. */
    val secret: String = "",
    /** Legacy forwarding without a secret trusts anyone who can reach the server; allow only on isolated networks. */
    val allowUnprotectedLegacyForwarding: Boolean = false,
)

/** How names without an account authenticate. */
enum class PremiumPolicy {
    /**
     * New players join without Mojang and choose: register with a password, or reconnect to log in through Mojang.
     * A failed licensed login brings them back to the choice.
     */
    MANUAL,
    /** Every new player registers with a password. */
    OFFLINE,
    /** Names owned by a licensed Mojang account must log in through Mojang; others register. */
    AUTO,
    /** Every new player must have a licensed account. */
    ONLINE,
}

@Serializable
data class AuthenticationConfig(
    /** `false` disables Mojang authentication entirely: every player registers and logs in with a password. */
    val licensedLogin: Boolean = true,
    val premiumPolicy: PremiumPolicy = PremiumPolicy.MANUAL,
    val loginTimeoutSeconds: Int = 60,
    val maxLoginAttempts: Int = 5,
    val minPasswordLength: Int = 6,
    val maxPasswordLength: Int = 64,
    val usernamePattern: String = "[A-Za-z0-9_]{3,16}",
    /** 0 disables the limit. */
    val maxAccountsPerIp: Int = 3,
    /** Players reconnecting from the same address within this time skip the password; 0 disables sessions. */
    val sessionMinutes: Int = 0,
    val unsafePasswords: List<String> = listOf("123456", "password", "qwerty", "12345678", "111111", "123123"),
    /** Encrypt offline connections of 1.20.5+ clients so passwords never travel in clear text (standalone only). */
    val encryptOfflineConnections: Boolean = true,
    val sessionServer: String = "https://sessionserver.mojang.com",
    val profileApi: String = "https://api.minecraftservices.com/minecraft/profile/lookup/name/",
    val hashing: HashingConfig = HashingConfig(),
    /** With the MANUAL policy, open the password/licensed choice menu on join (it is always on the help item). */
    val loginMenu: Boolean = true,
    /** Item in the first hotbar slot that explains how to log in on right click; empty for none. */
    val helpItem: String = "minecraft:compass",
    val showTitles: Boolean = true,
    val showBossBar: Boolean = true,
    val reminderSeconds: Int = 10,
)

@Serializable
data class HashingConfig(val memoryKib: Int = 19456, val iterations: Int = 2, val parallelism: Int = 1, val threads: Int = 2)

@Serializable
data class DatabaseSection(
    val type: DatabaseType = DatabaseType.SQLITE,
    val file: String = "data/auth.db",
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String = "authserver",
    val user: String = "authserver",
    val password: String = "",
    val poolSize: Int = 4,
    val tablePrefix: String = "authserver_",
)

@Serializable
data class PositionConfig(val x: Double = 0.5, val y: Double = 65.0, val z: Double = 0.5, val yaw: Float = 0f, val pitch: Float = 0f)

@Serializable
data class BlockPositionConfig(val x: Int = 0, val y: Int = 64, val z: Int = 0)

enum class LimboGameMode { ADVENTURE, SURVIVAL, SPECTATOR, CREATIVE }

@Serializable
data class WorldConfig(
    /** Schematic (.schem/.schematic), structure (.nbt) or Anvil world directory; empty for a single platform block. */
    val map: String = "",
    val placement: BlockPositionConfig = BlockPositionConfig(),
    val spawn: PositionConfig = PositionConfig(),
    val gameMode: LimboGameMode = LimboGameMode.ADVENTURE,
    val viewDistance: Int = 4,
    val timeOfDay: Long = 6000,
    /** Players falling below this height are teleported back to spawn. */
    val voidY: Int = -64,
    /** Players cannot walk further than this from spawn; 0 disables the limit. */
    val maxDistance: Int = 0,
)

@Serializable
data class StatusConfig(
    /** MiniMessage. */
    val motd: String = "<gradient:#5e4fa2:#f79459>AuthServer</gradient> <gray>- log in to play",
    val maxPlayers: Int = 1000,
    /** PNG 64x64; empty for none. */
    val favicon: String = "",
)

@Serializable
data class MessagesConfig(
    /** Directory with <language>.yml files; missing bundled files are copied into it. */
    val directory: String = "messages",
    val defaultLanguage: String = "en",
)

/** How the SMTP connection is secured. */
enum class MailSecurity {
    /** Plain connection, e.g. to a relay on localhost. */
    NONE,
    /** Upgrade with STARTTLS (usually port 587); refuses to send without it. */
    STARTTLS,
    /** TLS from the start (usually port 465). */
    SSL,
}

/** SMTP account for confirmation codes, email two-factor authentication and password recovery. */
@Serializable
data class EmailConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 587,
    val security: MailSecurity = MailSecurity.STARTTLS,
    val username: String = "",
    val password: String = "",
    /** Sender address; many providers require it to be the SMTP account itself. */
    val from: String = "",
    val fromName: String = "Minecraft server",
    /** How long a code stays valid. */
    val codeMinutes: Int = 10,
    /** Minimum time between two codes for the same account. */
    val resendSeconds: Int = 60,
    /** Wrong codes before a code is thrown away. */
    val maxCodeAttempts: Int = 5,
)

@Serializable
data class TwoFactorConfig(
    /** Lets players protect their accounts with /2fa. Existing settings keep working when turned off. */
    val enabled: Boolean = true,
    /** Name authenticator apps show next to the code. */
    val issuer: String = "Minecraft",
    /** Accepted clock drift in 30-second steps either way. */
    val totpWindow: Int = 1,
)

@Serializable
data class ApiConfig(
    /** HTTP API used by the Velocity plugin to decide online/offline mode at pre-login. */
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 8765,
)

@Serializable
data class TransferConfig(
    /** Standalone only: after authentication 1.20.5+ clients are transferred here. Empty keeps them in the limbo. */
    val host: String = "",
    val port: Int = 25565,
)
