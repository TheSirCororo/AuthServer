package ru.cororo.authserver.storage.importer

import org.slf4j.LoggerFactory
import ru.cororo.authserver.storage.AccountExistsException
import ru.cororo.authserver.storage.AccountRecord
import ru.cororo.authserver.storage.AccountRepository
import ru.cororo.authserver.storage.password.LegacyHashes
import ru.cororo.authserver.storage.password.PasswordHasher
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Plugins whose databases can be imported. Closed-source ones are read through column auto-detection. */
enum class ImportSource(internal val profile: ImportProfile?) {
    AUTHME(ImportProfile(
        tables = listOf("authme"), names = listOf("realname", "username"), passwords = listOf("password"),
        premiumUuids = listOf("premiumUUID"), registrationIps = listOf("regip"), registrationDates = listOf("regdate"),
        lastIps = listOf("ip"), lastLogins = listOf("lastlogin"),
    )),
    LIMBOAUTH(ImportProfile(
        tables = listOf("AUTH"), names = listOf("NICKNAME"), passwords = listOf("HASH"), premiumUuids = listOf("PREMIUMUUID"),
        registrationIps = listOf("IP"), registrationDates = listOf("REGDATE"), lastIps = listOf("LOGINIP"), lastLogins = listOf("LOGINDATE"),
    )),
    BUNGEEAUTH(ImportProfile(
        tables = listOf("BungeeAuth"), names = listOf("playername"), passwords = listOf("password"), hashTypes = listOf("pwtype"),
        registrationIps = listOf("registeredip"), lastIps = listOf("lastip"),
    )),
    SIMPLELOGIN(null),
    AUTHSYSTEM(null),
    XLOGIN(null),
    /** Any other plugin: the table and columns are guessed from their names. */
    AUTO(null);

    companion object {
        fun byName(name: String): ImportSource? = entries.firstOrNull { it.name.equals(name.replace("-", "").replace("_", ""), ignoreCase = true) }
    }
}

/** Candidate names per field; the first one present in the table wins. */
internal data class ImportProfile(
    val tables: List<String>,
    val names: List<String>,
    val passwords: List<String>,
    val hashTypes: List<String> = emptyList(),
    val premiumUuids: List<String> = emptyList(),
    val registrationIps: List<String> = emptyList(),
    val registrationDates: List<String> = emptyList(),
    val lastIps: List<String> = emptyList(),
    val lastLogins: List<String> = emptyList(),
)

/**
 * @param location JDBC URL (`jdbc:mysql://host/db`, `jdbc:postgresql://...`) or a SQLite (`.db`, `.sqlite`) or
 *   H2 (`.mv.db`) file
 * @param table table override; required when auto-detection finds several candidates
 * @param nameColumn column override for the player name
 * @param passwordColumn column override for the password hash
 * @param overwrite replace accounts that already exist here instead of skipping them
 * @param dryRun read and report without writing
 * @param plainTextPasswords the source stores passwords unhashed (hashed with Argon2id while importing)
 */
data class ImportOptions(
    val source: ImportSource,
    val location: String,
    val user: String? = null,
    val password: String? = null,
    val table: String? = null,
    val nameColumn: String? = null,
    val passwordColumn: String? = null,
    val overwrite: Boolean = false,
    val dryRun: Boolean = false,
    val plainTextPasswords: Boolean = false,
)

/**
 * @property unknownHashes imported accounts whose hash format is not recognised; they cannot log in until an
 *   administrator sets a password
 */
data class ImportReport(
    val table: String,
    val read: Int,
    val imported: Int,
    val premium: Int,
    val existing: Int,
    val invalid: Int,
    val unknownHashes: Int,
) {
    override fun toString() = "table $table: read $read, imported $imported ($premium licensed), already present $existing, " +
        "skipped $invalid invalid rows, $unknownHashes with unrecognised hashes"
}

/**
 * Copies accounts from another authentication plugin's database. Hashes are kept (tagged when their format has no
 * prefix of its own) and upgraded to Argon2id on the player's first login; plain-text passwords are hashed now.
 */
class AccountImporter(private val accounts: AccountRepository, private val hasher: PasswordHasher) {
    private val logger = LoggerFactory.getLogger(AccountImporter::class.java)

    fun run(options: ImportOptions): ImportReport = DriverManager.getConnection(jdbcUrl(options.location), options.user, options.password).use { connection ->
        val mapping = resolve(connection, options)
        logger.info("Importing {} from table {} ({})", options.source, mapping.table, mapping)
        var read = 0
        var imported = 0
        var premium = 0
        var existing = 0
        var invalid = 0
        var unknown = 0
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM ${quote(connection, mapping.table)}").use { rows ->
                while (rows.next()) {
                    read++
                    val record = runCatching { toRecord(rows, mapping, options.plainTextPasswords) }.onFailure { logger.debug("Invalid row", it) }.getOrNull()
                    if (record == null) {
                        invalid++
                        continue
                    }
                    if (record.passwordHash != null && isUnknown(record.passwordHash)) unknown++
                    if (options.dryRun) {
                        imported++
                        if (record.premium) premium++
                        continue
                    }
                    val current = accounts.find(record.username)
                    if (current != null && !options.overwrite) {
                        existing++
                        continue
                    }
                    current?.let { accounts.delete(it.id) }
                    try {
                        accounts.insert(record)
                        imported++
                        if (record.premium) premium++
                    } catch (_: AccountExistsException) {
                        existing++
                    }
                }
            }
        }
        ImportReport(mapping.table, read, imported, premium, existing, invalid, unknown)
    }

    internal data class Mapping(
        val table: String, val name: String, val password: String, val hashType: String?, val premiumUuid: String?,
        val registrationIp: String?, val registrationDate: String?, val lastIp: String?, val lastLogin: String?,
    )

    private fun resolve(connection: Connection, options: ImportOptions): Mapping {
        val tables = tables(connection)
        val profile = options.source.profile ?: AUTO_PROFILE
        val table = options.table?.let { wanted -> tables.keys.firstOrNull { it.equals(wanted, ignoreCase = true) } ?: error("No table $wanted") }
            ?: profile.tables.firstNotNullOfOrNull { wanted -> tables.keys.firstOrNull { it.equals(wanted, ignoreCase = true) } }
            ?: guessTable(tables, profile)
        val columns = tables.getValue(table)
        fun column(override: String?, candidates: List<String>): String? =
            override?.let { wanted -> columns.firstOrNull { it.equals(wanted, ignoreCase = true) } ?: error("No column $wanted in $table") }
                ?: candidates.firstNotNullOfOrNull { wanted -> columns.firstOrNull { it.equals(wanted, ignoreCase = true) } }
        return Mapping(
            table = table,
            name = column(options.nameColumn, profile.names) ?: error("Cannot find the player name column in $table: $columns"),
            password = column(options.passwordColumn, profile.passwords) ?: error("Cannot find the password column in $table: $columns"),
            hashType = column(null, profile.hashTypes),
            premiumUuid = column(null, profile.premiumUuids),
            registrationIp = column(null, profile.registrationIps),
            registrationDate = column(null, profile.registrationDates),
            lastIp = column(null, profile.lastIps),
            lastLogin = column(null, profile.lastLogins),
        )
    }

    /** The table that has both a name-like and a password-like column; ties prefer auth/user/account names. */
    private fun guessTable(tables: Map<String, List<String>>, profile: ImportProfile): String {
        val candidates = tables.filter { (_, columns) ->
            profile.names.any { name -> columns.any { it.equals(name, ignoreCase = true) } } &&
                profile.passwords.any { password -> columns.any { it.equals(password, ignoreCase = true) } }
        }.keys
        return candidates.maxByOrNull { table -> PREFERRED_TABLE_WORDS.count { it in table.lowercase() } }
            ?: error("No table with player names and passwords found; tables: ${tables.keys}. Pass table=, name-column= and password-column=")
    }

    private fun tables(connection: Connection): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        connection.metaData.getTables(connection.catalog, null, "%", arrayOf("TABLE")).use { tables ->
            while (tables.next()) {
                val name = tables.getString("TABLE_NAME")
                val schema = tables.getString("TABLE_SCHEM")
                if (schema != null && schema.equals("INFORMATION_SCHEMA", ignoreCase = true)) continue
                val columns = mutableListOf<String>()
                connection.metaData.getColumns(connection.catalog, schema, name, "%").use { rows ->
                    while (rows.next()) columns += rows.getString("COLUMN_NAME")
                }
                result[name] = columns
            }
        }
        return result
    }

    private fun toRecord(rows: ResultSet, mapping: Mapping, plainText: Boolean): AccountRecord? {
        val username = rows.getString(mapping.name)?.trim()?.takeIf { it.matches(USERNAME) } ?: return null
        val premiumUuid = mapping.premiumUuid?.let(rows::getString)?.trim()?.takeIf(String::isNotEmpty)?.let(::parseUuid)
        val raw = rows.getString(mapping.password)?.trim()?.takeIf(String::isNotEmpty)
        val hash = raw?.let { normalise(it, mapping.hashType?.let(rows::getString), plainText) }
        if (hash == null && premiumUuid == null) return null
        return AccountRecord(
            username = username,
            passwordHash = hash,
            premium = hash == null,
            premiumUuid = premiumUuid,
            registeredAt = mapping.registrationDate?.let { instant(rows, it) } ?: Instant.now(),
            registrationIp = mapping.registrationIp?.let(rows::getString)?.takeIf(String::isNotBlank),
            lastLoginAt = mapping.lastLogin?.let { instant(rows, it) },
            lastLoginIp = mapping.lastIp?.let(rows::getString)?.takeIf(String::isNotBlank),
        )
    }

    /** Converts a stored hash to a form [PasswordHasher] recognises. */
    internal fun normalise(raw: String, bungeeAuthType: String?, plainText: Boolean = false): String = when (bungeeAuthType?.trim()) {
        "0", "7" -> LegacyHashes.XAUTH + raw
        "1" -> LegacyHashes.WHIRLPOOL + raw
        "2" -> LegacyHashes.MD5 + raw
        "3" -> LegacyHashes.SHA1 + raw
        "4" -> LegacyHashes.SHA256 + raw
        "6" -> LegacyHashes.PBKDF2_SHA1 + raw
        else -> detect(raw, plainText)
    }

    private fun detect(raw: String, plainText: Boolean): String = when {
        KNOWN_PREFIXES.any(raw::startsWith) -> raw
        raw.matches(PBKDF2_SHA1) -> LegacyHashes.PBKDF2_SHA1 + raw
        raw.matches(HEX) -> when (raw.length) {
            32 -> LegacyHashes.MD5 + raw
            40 -> LegacyHashes.SHA1 + raw
            64 -> LegacyHashes.SHA256 + raw
            128 -> LegacyHashes.SHA512 + raw
            else -> raw
        }
        // Only on request: guessing would turn an unknown hash into a password that equals the hash.
        plainText -> hasher.hash(raw)
        else -> raw
    }

    private fun isUnknown(hash: String) = (KNOWN_PREFIXES + LegacyHashes.TAGS).none(hash::startsWith)

    /** Dates are epoch seconds, epoch milliseconds or SQL timestamps depending on the plugin. */
    private fun instant(rows: ResultSet, column: String): Instant? {
        val value = rows.getObject(column) ?: return null
        return when (value) {
            is Timestamp -> value.toInstant()
            is Number -> value.toLong().takeIf { it > 0 }?.let { if (it < 100_000_000_000L) Instant.ofEpochSecond(it) else Instant.ofEpochMilli(it) }
            is String -> value.toLongOrNull()?.let { if (it < 100_000_000_000L) Instant.ofEpochSecond(it) else Instant.ofEpochMilli(it) }
            else -> null
        }
    }

    private fun parseUuid(value: String): UUID? = runCatching {
        if ('-' in value) UUID.fromString(value)
        else UUID(java.lang.Long.parseUnsignedLong(value.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(value.substring(16), 16))
    }.getOrNull()

    private fun quote(connection: Connection, identifier: String): String {
        val quote = connection.metaData.identifierQuoteString.trim().ifEmpty { "\"" }
        return quote + identifier + quote
    }

    companion object {
        private val USERNAME = Regex("[A-Za-z0-9_]{1,16}")
        private val HEX = Regex("[0-9a-fA-F]+")
        private val PBKDF2_SHA1 = Regex("\\d+:[0-9a-fA-F]+:[0-9a-fA-F]+")
        private val KNOWN_PREFIXES = listOf("\$2a\$", "\$2b\$", "\$2y\$", "\$argon2", "\$SHA\$", "\$SHA512\$", "pbkdf2_sha256\$", "pbkdf2\$")
        private val PREFERRED_TABLE_WORDS = listOf("auth", "user", "account", "player", "login")
        private val AUTO_PROFILE = ImportProfile(
            tables = emptyList(),
            names = listOf("realname", "nickname", "username", "playername", "player_name", "user_name", "name", "nick", "player", "user"),
            passwords = listOf("password", "password_hash", "passwordhash", "hash", "pass", "pwd", "hashed_password"),
            premiumUuids = listOf("premiumuuid", "premium_uuid"),
            registrationIps = listOf("regip", "registration_ip", "register_ip", "ip"),
            registrationDates = listOf("regdate", "registered_at", "register_date", "created_at"),
            lastIps = listOf("lastip", "last_ip", "loginip", "login_ip"),
            lastLogins = listOf("lastlogin", "last_login", "logindate", "last_login_at"),
        )

        /** Builds a JDBC URL for database files and makes MySQL URLs use the bundled MariaDB driver. */
        fun jdbcUrl(location: String): String = when {
            location.startsWith("jdbc:mysql:") -> "jdbc:mariadb:" + location.removePrefix("jdbc:mysql:")
            location.startsWith("jdbc:") -> location
            location.endsWith(".mv.db") -> "jdbc:h2:file:" + Path.of(location.removeSuffix(".mv.db")).toAbsolutePath() + ";ACCESS_MODE_DATA=r"
            else -> "jdbc:sqlite:" + Path.of(location).toAbsolutePath()
        }
    }
}
