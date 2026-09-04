package com.jdandroid

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jdandroid.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueft die Room-Migrationen gegen die exportierten Schemata in app/schemas.
 * Laeuft nur auf Geraet/Emulator (connectedDebugAndroidTest); im CI wird der
 * Test lediglich kompiliert.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate5To6_addsOnlineColumn() {
        helper.createDatabase(dbName, 5).apply {
            execSQL("INSERT INTO packages (id, name, autoNamed, source, addedAt) VALUES (1, 'Paket', 1, NULL, 1)")
            execSQL(
                "INSERT INTO downloads (id, url, hosterId, packageId, fileName, fileSize, downloadedBytes, " +
                    "speedBps, status, errorMessage, localPath, attempts, retryAt, addedAt) VALUES " +
                    "(1, 'https://example.org/a.bin', 'ddownload', 1, 'a.bin', 10, 0, 0, 'QUEUED', NULL, NULL, 0, 0, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        db.query("SELECT online FROM downloads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun migrate6To7_addsTrafficColumns() {
        helper.createDatabase(dbName, 6).apply {
            execSQL(
                "INSERT INTO accounts (id, hosterId, username, password, apiKey, cookies, premiumUntil, " +
                    "trafficLeft, valid, lastChecked, statusText) VALUES " +
                    "(1, 'ddownload', 'user', 'pw', NULL, NULL, 0, -1, 1, 0, NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)
        db.query("SELECT trafficTotal, trafficUnlimited FROM accounts WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(-1L, c.getLong(0))
            assertEquals(0, c.getInt(1))
        }
    }

    @Test
    fun migrateAll5To7() {
        helper.createDatabase(dbName, 5).close()
        helper.runMigrationsAndValidate(
            dbName, 7, true,
            AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7
        )
    }
}
