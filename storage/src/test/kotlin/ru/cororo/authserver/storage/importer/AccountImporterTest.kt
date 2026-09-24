package ru.cororo.authserver.storage.importer

import com.password4j.Argon2Function
import com.password4j.BcryptFunction
import com.password4j.Password
import com.password4j.types.Argon2
import com.password4j.types.Bcrypt
import org.junit.jupiter.api.io.TempDir
import ru.cororo.authserver.storage.Database
import ru.cororo.authserver.storage.DatabaseConfig
import ru.cororo.authserver.storage.password.Argon2Settings
import ru.cororo.authserver.storage.password.PasswordHasher
import ru.cororo.authserver.storage.password.Whirlpool
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountImporterTest {
    @TempDir
    lateinit var directory: Path

    private val hasher = PasswordHasher(Argon2Settings(memoryKib = 1024, iterations = 1))
    private lateinit var database: Database
    private lateinit var importer: AccountImporter

    @BeforeTest
    fun open() {
        database = Database(DatabaseConfig(file = directory.resolve("target.db").toString()))
        importer = AccountImporter(database.accounts, hasher)
    }

    @AfterTest
    fun close() = database.close()

    private fun source(name: String, vararg statements: String): String {
        val file = directory.resolve(name).toString()
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement -> statements.forEach(statement::execute) }
        }
        return file
    }

    private fun assertLogsIn(name: String, password: String) {
        val account = assertNotNull(database.accounts.find(name), "$name imported")
        val verification = hasher.verify(password, assertNotNull(account.passwordHash))
        assertTrue(verification.valid, "$name accepts its password")
        assertTrue(verification.needsRehash, "$name is upgraded to Argon2id on login")
        assertFalse(hasher.verify("$password-wrong", account.passwordHash!!).valid, "$name rejects wrong passwords")
    }

    @Test
    fun `authme accounts keep their hashes and dates`() {
        val salt = "0123456789abcdef"
        val sha = "\$SHA\$$salt\$" + hex("SHA-256", hex("SHA-256", "steve-pass") + salt)
        val bcrypt = Password.hash("alex-pass").with(BcryptFunction.getInstance(Bcrypt.Y, 6)).result
        val argon2i = Password.hash("bob-pass").addRandomSalt(16).with(Argon2Function.getInstance(1024, 2, 1, 32, Argon2.I)).result
        val pbkdf2 = "pbkdf2_sha256\$1000\$salty\$" + pbkdf2("PBKDF2WithHmacSHA256", "carl-pass", "salty".toByteArray(), 1000, 64)
            .joinToString("") { "%02X".format(it) }
        val file = source("authme.db",
            "CREATE TABLE authme (id INTEGER PRIMARY KEY, username VARCHAR(255), realname VARCHAR(255), password VARCHAR(255), " +
                "ip VARCHAR(40), lastlogin BIGINT, regdate BIGINT, regip VARCHAR(40), email VARCHAR(255))",
            "INSERT INTO authme (username, realname, password, ip, lastlogin, regdate, regip) VALUES " +
                "('steve', 'Steve', '$sha', '10.0.0.2', 1700000001000, 1600000000000, '10.0.0.1'), " +
                "('alex', 'Alex', '$bcrypt', NULL, 0, 1600000000000, NULL), " +
                "('bob', 'Bob', '$argon2i', NULL, 0, 1600000000000, NULL), " +
                "('carl', 'Carl', '$pbkdf2', NULL, 0, 1600000000000, NULL)",
        )
        val report = importer.run(ImportOptions(ImportSource.AUTHME, file))
        assertEquals(4, report.imported, report.toString())
        assertLogsIn("Steve", "steve-pass")
        assertLogsIn("Alex", "alex-pass")
        assertLogsIn("Bob", "bob-pass")
        assertLogsIn("Carl", "carl-pass")
        val steve = database.accounts.find("steve")!!
        assertEquals("Steve", steve.username, "The real name keeps its case")
        assertEquals(Instant.ofEpochMilli(1600000000000), steve.registeredAt)
        assertEquals("10.0.0.1", steve.registrationIp)
        assertEquals("10.0.0.2", steve.lastLoginIp)
    }

    @Test
    fun `limboauth licensed accounts stay licensed`() {
        val premium = UUID.randomUUID()
        val bcrypt = Password.hash("dan-pass").with(BcryptFunction.getInstance(Bcrypt.A, 6)).result
        val file = source("limboauth.db",
            "CREATE TABLE AUTH (NICKNAME VARCHAR, LOWERCASENICKNAME VARCHAR PRIMARY KEY, HASH VARCHAR, IP VARCHAR, TOTPTOKEN VARCHAR, " +
                "REGDATE BIGINT, UUID VARCHAR, PREMIUMUUID VARCHAR, LOGINIP VARCHAR, LOGINDATE BIGINT, ISSUEDTIME BIGINT)",
            "INSERT INTO AUTH VALUES ('Dan', 'dan', '$bcrypt', '1.1.1.1', '', 1600000000000, '', '', '1.1.1.1', 1700000000000, 0), " +
                "('Notch', 'notch', '', '2.2.2.2', '', 1600000000000, '', '$premium', '2.2.2.2', 1700000000000, 0)",
        )
        val report = importer.run(ImportOptions(ImportSource.LIMBOAUTH, file))
        assertEquals(2, report.imported)
        assertEquals(1, report.premium)
        assertLogsIn("Dan", "dan-pass")
        val notch = database.accounts.find("Notch")!!
        assertTrue(notch.premium)
        assertEquals(premium, notch.premiumUuid)
    }

    @Test
    fun `bungeeauth hash types are decoded`() {
        val salt = "0123456789ab".toByteArray()
        val pbkdf2 = "1000:${salt.hex()}:${pbkdf2("PBKDF2WithHmacSHA1", "eve-pass", salt, 1000, 64).hex()}"
        val md5 = hex("MD5", "fay-pass")
        val xauthSalt = "abcdefghijkl"
        val digest = Whirlpool.hex(xauthSalt + "gus-pass")
        val xauth = digest.substring(0, "gus-pass".length) + xauthSalt + digest.substring("gus-pass".length)
        val file = source("bungeeauth.db",
            "CREATE TABLE BungeeAuth (id INTEGER, playername VARCHAR(255), password VARCHAR(255), pwtype TINYINT, email VARCHAR(255), " +
                "registeredip VARCHAR(255), lastip VARCHAR(255), version VARCHAR(255), status VARCHAR(255))",
            "INSERT INTO BungeeAuth VALUES (1, 'Eve', '$pbkdf2', 6, '', '3.3.3.3', '3.3.3.4', '', 'online'), " +
                "(2, 'Fay', '$md5', 2, '', NULL, NULL, '', ''), (3, 'Gus', '$xauth', 0, '', NULL, NULL, '', '')",
        )
        assertEquals(3, importer.run(ImportOptions(ImportSource.BUNGEEAUTH, file)).imported)
        assertLogsIn("Eve", "eve-pass")
        assertLogsIn("Fay", "fay-pass")
        assertLogsIn("Gus", "gus-pass")
        assertEquals("3.3.3.3", database.accounts.find("Eve")!!.registrationIp)
    }

    @Test
    fun `unknown plugins are auto-detected`() {
        val bcrypt = Password.hash("hal-pass").with(BcryptFunction.getInstance(Bcrypt.B, 6)).result
        val file = source("other.db",
            "CREATE TABLE stats (name VARCHAR, kills INT)",
            "INSERT INTO stats VALUES ('Hal', 3)",
            "CREATE TABLE login_users (id INTEGER, player_name VARCHAR, password_hash VARCHAR, created_at BIGINT)",
            "INSERT INTO login_users VALUES (1, 'Hal', '$bcrypt', 1600000000), (2, 'bad name!', '$bcrypt', 0), (3, 'Ivy', 'no-idea', 0)",
        )
        val report = importer.run(ImportOptions(ImportSource.SIMPLELOGIN, file))
        assertEquals("login_users", report.table)
        assertEquals(2, report.imported, report.toString())
        assertEquals(1, report.invalid)
        assertEquals(1, report.unknownHashes)
        assertLogsIn("Hal", "hal-pass")
        assertEquals(Instant.ofEpochSecond(1600000000), database.accounts.find("Hal")!!.registeredAt, "Epoch seconds are recognised")
        assertFalse(hasher.verify("no-idea", database.accounts.find("Ivy")!!.passwordHash!!).valid, "Unknown hashes never match")
    }

    @Test
    fun `h2 databases such as xlogin can be read`() {
        val bcrypt = Password.hash("jim-pass").with(BcryptFunction.getInstance(Bcrypt.A, 6)).result
        val file = directory.resolve("data")
        DriverManager.getConnection("jdbc:h2:file:$file").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE XLOGIN_PLAYERS (UUID VARCHAR, USERNAME VARCHAR, PASSWORD VARCHAR, IP VARCHAR)")
                it.execute("INSERT INTO XLOGIN_PLAYERS VALUES ('x', 'Jim', '$bcrypt', '4.4.4.4')")
            }
        }
        assertEquals(1, importer.run(ImportOptions(ImportSource.XLOGIN, "$file.mv.db")).imported)
        assertLogsIn("Jim", "jim-pass")
    }

    @Test
    fun `existing accounts are kept unless overwriting, dry runs write nothing`() {
        database.accounts.create("Steve", hasher.hash("mine"), null)
        val file = source("plain.db", "CREATE TABLE users (username VARCHAR, password VARCHAR)",
            "INSERT INTO users VALUES ('Steve', 'theirs'), ('Kim', 'kim-pass')")
        val dry = importer.run(ImportOptions(ImportSource.AUTO, file, plainTextPasswords = true, dryRun = true))
        assertEquals(2, dry.imported)
        assertEquals(null, database.accounts.find("Kim"), "Dry run writes nothing")
        val first = importer.run(ImportOptions(ImportSource.AUTO, file, plainTextPasswords = true))
        assertEquals(1, first.existing)
        assertTrue(hasher.verify("mine", database.accounts.find("Steve")!!.passwordHash!!).valid)
        val kim = hasher.verify("kim-pass", database.accounts.find("Kim")!!.passwordHash!!)
        assertEquals(true, kim.valid)
        assertFalse(kim.needsRehash, "Plain-text passwords are hashed with Argon2id while importing")
        importer.run(ImportOptions(ImportSource.AUTO, file, plainTextPasswords = true, overwrite = true))
        assertTrue(hasher.verify("theirs", database.accounts.find("Steve")!!.passwordHash!!).valid)
    }

    private fun hex(algorithm: String, value: String) =
        MessageDigest.getInstance(algorithm).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun pbkdf2(algorithm: String, password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray =
        SecretKeyFactory.getInstance(algorithm).generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, bytes * 8)).encoded
}
