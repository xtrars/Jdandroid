package com.jdandroid

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jdandroid.data.AppDatabase
import com.jdandroid.data.DownloadNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun migrate7To8_dedupeBehaeltKleinsteId() {
        helper.createDatabase(dbName, 7).apply {
            insertDownload(this, 1, "https://example.org/a.bin")
            insertDownload(this, 2, "https://example.org/a.bin")
            insertDownload(this, 3, "https://example.org/a.bin")
            insertDownload(this, 4, "https://example.org/b.bin")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 8, true, AppDatabase.MIGRATION_7_8)
        db.query("SELECT id FROM downloads ORDER BY id").use { c ->
            assertEquals(2, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertTrue(c.moveToNext())
            assertEquals(4L, c.getLong(0))
        }
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_downloads_url'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun migrate8To9AddsExtractProgress() {
        helper.createDatabase(dbName, 8).apply {
            insertDownload(this, 1, "https://example.org/a.bin")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 9, true, AppDatabase.MIGRATION_8_9)
        db.query("SELECT extractProgress FROM downloads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(-1, c.getInt(0))
        }
    }

    @Test
    fun migrate9To10_fuelltArchiveKeyAusDemDateinamen() {
        helper.createDatabase(dbName, 9).apply {
            insertDownload9(this, 1, "https://example.org/a", "x.part2.rar")
            insertDownload9(this, 2, "https://example.org/b", "x part1 rar")
            insertDownload9(this, 3, "https://example.org/c", "film.mkv")
            insertDownload9(this, 4, "https://example.org/d", null)
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 10, true, AppDatabase.MIGRATION_9_10)
        db.query("SELECT id, archiveKey FROM downloads ORDER BY id").use { c ->
            assertEquals(4, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("x", c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals("x", c.getString(1))
            assertTrue(c.moveToNext())
            assertTrue(c.isNull(1))
            assertTrue(c.moveToNext())
            assertTrue(c.isNull(1))
        }
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_downloads_archiveKey'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun migrate10To11_entferntExtractProgressWandeltVermerkeUndBehaeltDaten() {
        helper.createDatabase(dbName, 10).apply {
            execSQL("INSERT INTO packages (id, name, autoNamed, source, addedAt) VALUES (1, 'Paket', 1, NULL, 1)")
            insertDownload10(this, 1, "https://example.org/a", "x.part1.rar", "x", 1, 50, DownloadNotes.LEGACY_WAITING_PARTS)
            insertDownload10(this, 2, "https://example.org/b", "film.mkv", null, null, -1, DownloadNotes.LEGACY_WAITING_WIFI)
            insertDownload10(this, 3, "https://example.org/c", "c.bin", null, null, -1, "Hoster-Meldung")
            insertDownload10(this, 4, "https://example.org/d", null, null, null, -1, null)
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 11, true, AppDatabase.MIGRATION_10_11)
        db.query(
            "SELECT id, url, fileName, archiveKey, packageId, status, downloadedBytes, errorMessage FROM downloads ORDER BY id"
        ).use { c ->
            assertEquals(4, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("https://example.org/a", c.getString(1))
            assertEquals("x.part1.rar", c.getString(2))
            assertEquals("x", c.getString(3))
            assertEquals(1L, c.getLong(4))
            assertEquals("EXTRACTING", c.getString(5))
            assertEquals(10L, c.getLong(6))
            assertEquals(DownloadNotes.WAITING_PARTS, c.getString(7))
            assertTrue(c.moveToNext())
            assertEquals(2L, c.getLong(0))
            assertTrue(c.isNull(3))
            assertTrue(c.isNull(4))
            assertEquals(DownloadNotes.WAITING_WIFI, c.getString(7))
            assertTrue(c.moveToNext())
            assertEquals("Hoster-Meldung", c.getString(7))
            assertTrue(c.moveToNext())
            assertTrue(c.isNull(7))
        }
        db.query("PRAGMA table_info(downloads)").use { c ->
            val columns = generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
            assertFalse(columns.contains("extractProgress"))
            assertTrue(columns.contains("addedAt"))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'downloads'").use { c ->
            val indices = generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
            assertTrue(
                indices.containsAll(
                    listOf(
                        "index_downloads_url", "index_downloads_archiveKey",
                        "index_downloads_status", "index_downloads_packageId"
                    )
                )
            )
        }
        // AUTOINCREMENT laeuft nach dem Neuaufbau hinter den vorhandenen Ids weiter
        db.execSQL(
            "INSERT INTO downloads (url, hosterId, fileSize, downloadedBytes, speedBps, status, attempts, " +
                "retryAt, online, addedAt) VALUES ('https://example.org/e', 'ddownload', -1, 0, 0, 'QUEUED', 0, 0, 0, 1)"
        )
        db.query("SELECT MAX(id) FROM downloads").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(5L, c.getLong(0))
        }
    }

    @Test
    fun migrateAll5To11() {
        helper.createDatabase(dbName, 5).apply {
            execSQL("INSERT INTO packages (id, name, autoNamed, source, addedAt) VALUES (1, 'Paket', 1, NULL, 1)")
            execSQL(
                "INSERT INTO downloads (id, url, hosterId, packageId, fileName, fileSize, downloadedBytes, " +
                    "speedBps, status, errorMessage, localPath, attempts, retryAt, addedAt) VALUES " +
                    "(1, 'https://example.org/a.part1.rar', 'ddownload', 1, 'a.part1.rar', 10, 10, 0, 'COMPLETED', NULL, NULL, 0, 0, 1)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 11, true, *AppDatabase.ALL_MIGRATIONS)
        db.query("SELECT url, archiveKey, packageId FROM downloads WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("https://example.org/a.part1.rar", c.getString(0))
            assertEquals("a", c.getString(1))
            assertEquals(1L, c.getLong(2))
        }
    }

    /** Fuegt eine downloads-Zeile im Schema der Version 10 ein (mit extractProgress und archiveKey) samt Vermerk. */
    private fun insertDownload10(
        db: SupportSQLiteDatabase, id: Long, url: String, fileName: String?, archiveKey: String?,
        packageId: Long?, extractProgress: Int, note: String?
    ) {
        db.execSQL(
            "INSERT INTO downloads (id, url, hosterId, packageId, fileName, archiveKey, fileSize, downloadedBytes, " +
                "speedBps, status, errorMessage, localPath, attempts, retryAt, online, extractProgress, addedAt) VALUES " +
                "(?, ?, 'ddownload', ?, ?, ?, 10, 10, 0, 'EXTRACTING', ?, NULL, 0, 0, 0, ?, 1)",
            arrayOf<Any?>(id, url, packageId, fileName, archiveKey, note, extractProgress)
        )
    }

    /** Fuegt eine downloads-Zeile im Schema der Version 9 ein (mit extractProgress, ohne archiveKey). */
    private fun insertDownload9(db: SupportSQLiteDatabase, id: Long, url: String, fileName: String?) {
        db.execSQL(
            "INSERT INTO downloads (id, url, hosterId, packageId, fileName, fileSize, downloadedBytes, " +
                "speedBps, status, errorMessage, localPath, attempts, retryAt, online, extractProgress, addedAt) VALUES " +
                "(?, ?, 'ddownload', NULL, ?, 10, 0, 0, 'COMPLETED', NULL, NULL, 0, 0, 0, -1, 1)",
            arrayOf<Any?>(id, url, fileName)
        )
    }

    /** Fuegt eine downloads-Zeile im Schema der Versionen 7 und 8 ein (Spalte online vorhanden). */
    private fun insertDownload(db: SupportSQLiteDatabase, id: Long, url: String) {
        db.execSQL(
            "INSERT INTO downloads (id, url, hosterId, packageId, fileName, fileSize, downloadedBytes, " +
                "speedBps, status, errorMessage, localPath, attempts, retryAt, online, addedAt) VALUES " +
                "($id, '$url', 'ddownload', NULL, 'a.bin', 10, 0, 0, 'QUEUED', NULL, NULL, 0, 0, 0, 1)"
        )
    }
}
