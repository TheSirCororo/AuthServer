package ru.cororo.authserver.storage

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.util.UUID

internal class JdbcAccountRepository(private val database: Database, prefix: String) : AccountRepository {
    private val table = "${prefix}accounts"
    private val columns = "id, username, password_hash, premium, premium_uuid, registered_at, registration_ip, last_login_at, " +
        "last_login_ip, email, two_factor, totp_secret"

    override fun find(username: String): Account? =
        querySingle("SELECT $columns FROM $table WHERE username_lower = ?") { it.setString(1, username.lowercase()) }

    override fun findByPremiumUuid(uuid: UUID): Account? =
        querySingle("SELECT $columns FROM $table WHERE premium_uuid = ?") { it.setString(1, uuid.toString()) }

    override fun insert(record: AccountRecord): Account {
        try {
            database.connection { connection ->
                connection.prepareStatement(
                    "INSERT INTO $table (username, username_lower, password_hash, premium, premium_uuid, registered_at, " +
                        "registration_ip, last_login_at, last_login_ip, email, two_factor, totp_secret) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, record.username)
                    statement.setString(2, record.username.lowercase())
                    statement.setNullableString(3, record.passwordHash)
                    statement.setInt(4, if (record.premium) 1 else 0)
                    statement.setNullableString(5, record.premiumUuid?.toString())
                    statement.setLong(6, record.registeredAt.toEpochMilli())
                    statement.setNullableString(7, record.registrationIp)
                    if (record.lastLoginAt == null) statement.setNull(8, Types.BIGINT) else statement.setLong(8, record.lastLoginAt.toEpochMilli())
                    statement.setNullableString(9, record.lastLoginIp)
                    statement.setNullableString(10, record.email)
                    statement.setString(11, record.twoFactor.name)
                    statement.setNullableString(12, record.totpSecret)
                    statement.executeUpdate()
                }
            }
        } catch (exception: SQLException) {
            // Unique violations have vendor-specific codes; re-check instead of parsing them.
            if (find(record.username) != null) throw AccountExistsException(record.username)
            throw exception
        }
        return checkNotNull(find(record.username)) { "Account ${record.username} vanished after insert" }
    }

    override fun updatePassword(id: Long, passwordHash: String) =
        update("UPDATE $table SET password_hash = ? WHERE id = ?") {
            it.setString(1, passwordHash)
            it.setLong(2, id)
        }

    override fun recordLogin(id: Long, ip: String?, at: Instant) =
        update("UPDATE $table SET last_login_at = ?, last_login_ip = ? WHERE id = ?") {
            it.setLong(1, at.toEpochMilli())
            it.setNullableString(2, ip)
            it.setLong(3, id)
        }

    override fun clearSession(id: Long) = update("UPDATE $table SET last_login_at = NULL WHERE id = ?") { it.setLong(1, id) }

    override fun setPremium(id: Long, premium: Boolean, premiumUuid: UUID?) =
        update("UPDATE $table SET premium = ?, premium_uuid = ? WHERE id = ?") {
            it.setInt(1, if (premium) 1 else 0)
            it.setNullableString(2, premiumUuid?.toString())
            it.setLong(3, id)
        }

    override fun setEmail(id: Long, email: String?) =
        update("UPDATE $table SET email = ? WHERE id = ?") {
            it.setNullableString(1, email)
            it.setLong(2, id)
        }

    override fun setTwoFactor(id: Long, method: TwoFactorMethod, totpSecret: String?) {
        require((method == TwoFactorMethod.TOTP) == (totpSecret != null)) { "A TOTP secret goes with the TOTP method only" }
        update("UPDATE $table SET two_factor = ?, totp_secret = ? WHERE id = ?") {
            it.setString(1, method.name)
            it.setNullableString(2, totpSecret)
            it.setLong(3, id)
        }
    }

    override fun delete(id: Long) = update("DELETE FROM $table WHERE id = ?") { it.setLong(1, id) }

    override fun countByRegistrationIp(ip: String): Int = database.connection { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE registration_ip = ?").use { statement ->
            statement.setString(1, ip)
            statement.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }
    }

    private fun querySingle(sql: String, bind: (PreparedStatement) -> Unit): Account? = database.connection { connection ->
        connection.prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { if (it.next()) it.toAccount() else null }
        }
    }

    private fun update(sql: String, bind: (PreparedStatement) -> Unit) {
        database.connection { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeUpdate()
            }
        }
    }

    private fun ResultSet.toAccount() = Account(
        id = getLong("id"),
        username = getString("username"),
        passwordHash = getString("password_hash"),
        premium = getInt("premium") != 0,
        premiumUuid = getString("premium_uuid")?.let(UUID::fromString),
        registeredAt = Instant.ofEpochMilli(getLong("registered_at")),
        registrationIp = getString("registration_ip"),
        lastLoginAt = getLong("last_login_at").takeUnless { wasNull() }?.let(Instant::ofEpochMilli),
        lastLoginIp = getString("last_login_ip"),
        email = getString("email"),
        twoFactor = getString("two_factor")?.let { runCatching { TwoFactorMethod.valueOf(it) }.getOrNull() } ?: TwoFactorMethod.NONE,
        totpSecret = getString("totp_secret"),
    )

    private fun PreparedStatement.setNullableString(index: Int, value: String?) =
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}
