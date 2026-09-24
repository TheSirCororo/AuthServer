package ru.cororo.authserver.storage

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseTest {
    @TempDir
    lateinit var directory: Path

    private fun open(type: DatabaseType) = Database(DatabaseConfig(type = type, file = directory.resolve("auth").toString()))

    @ParameterizedTest
    @EnumSource(value = DatabaseType::class, names = ["SQLITE", "H2"])
    fun `accounts lifecycle`(type: DatabaseType) {
        open(type).use { database ->
            val accounts = database.accounts
            val created = accounts.create("Steve", "hash", "10.0.0.1")
            assertEquals("Steve", created.username)
            assertEquals(created, accounts.find("STEVE"), "Lookups ignore case")
            assertFailsWith<AccountExistsException> { accounts.create("steve", "other", "10.0.0.2") }

            accounts.updatePassword(created.id, "new-hash")
            accounts.recordLogin(created.id, "10.0.0.3")
            val uuid = UUID.randomUUID()
            accounts.setPremium(created.id, true, uuid)
            val updated = assertNotNull(accounts.find("steve"))
            assertEquals("new-hash", updated.passwordHash)
            assertEquals("10.0.0.3", updated.lastLoginIp)
            assertNotNull(updated.lastLoginAt)
            assertTrue(updated.premium)
            assertEquals(updated, accounts.findByPremiumUuid(uuid))

            accounts.create("Alex", null, "10.0.0.1")
            assertEquals(2, accounts.countByRegistrationIp("10.0.0.1"))
            accounts.delete(created.id)
            assertNull(accounts.find("Steve"))
        }
        // Reopening must not re-run migrations or lose data.
        open(type).use { assertNotNull(it.accounts.find("alex")) }
    }
}
