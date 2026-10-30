package com.jdandroid

import com.jdandroid.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Migration 11 -> 12 on the JVM: premium becomes a column, filled from the
 * status prefix ("Premium", "Ultimate") that decided it until version 11.
 */
class Migration11To12Test {

    private lateinit var db: Connection

    @Before
    fun openDb() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        MigrationSql.createSchema(db, 11)
    }

    @After
    fun closeDb() = db.close()

    private fun account(id: Long, status: String?, premiumUntil: Long = 0) {
        db.prepareStatement(
            "INSERT INTO accounts (id, hosterId, username, password, apiKey, cookies, premiumUntil, trafficLeft, " +
                "trafficTotal, trafficUnlimited, valid, lastChecked, statusText) " +
                "VALUES (?, 'ddownload', 'u', 'p', NULL, NULL, ?, 5, 10, 0, 1, 3, ?)"
        ).use {
            it.setLong(1, id); it.setLong(2, premiumUntil); it.setString(3, status)
            it.executeUpdate()
        }
    }

    private fun premium(id: Long): Int =
        db.prepareStatement("SELECT premium FROM accounts WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    @Test
    fun premiumKommtAusDemBisherigenStatustext() {
        account(1, "Premium")
        account(2, "Ultimate · Kontingent nicht lesbar")
        account(3, "Premium/Access · CDN 2 GB")
        account(4, "Free")
        account(5, null)
        // The old rule was case-sensitive, a lower-case "premium" never counted
        account(6, "premium?")
        // Expiry dates keep deciding on their own in hasPremium
        account(7, "Free", premiumUntil = 4_102_444_800_000)
        AppDatabase.MIGRATION_11_12.migrate(MigrationSql.roomDb(db))
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 0), (1L..7L).map { premium(it) })
    }

    @Test
    fun spaltenWieImSchema12UndDatenBleiben() {
        account(1, "Premium", premiumUntil = 42)
        AppDatabase.MIGRATION_11_12.migrate(MigrationSql.roomDb(db))
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            MigrationSql.createSchema(fresh, 12)
            assertEquals(MigrationSql.columns(fresh, "accounts"), MigrationSql.columns(db, "accounts"))
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT * FROM accounts WHERE id = 1").use { rs ->
                rs.next()
                assertEquals("u", rs.getString("username"))
                assertEquals(42L, rs.getLong("premiumUntil"))
                assertEquals(5L, rs.getLong("trafficLeft"))
                assertEquals(10L, rs.getLong("trafficTotal"))
                assertEquals(1, rs.getInt("valid"))
                assertEquals(3L, rs.getLong("lastChecked"))
                assertEquals("Premium", rs.getString("statusText"))
            }
        }
    }
}
