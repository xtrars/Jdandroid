package com.jdandroid

import com.jdandroid.data.AppDatabase
import com.jdandroid.data.DownloadNotes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Runs the real Room migration 10 -> 11 against SQLite on the JVM: a schema-10
 * database is built from the exported schema and the migration's SQL is fed
 * through [MigrationSql.roomDb].
 */
class Migration10To11Test {

    private lateinit var db: Connection

    @Before
    fun openDb() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        MigrationSql.createSchema(db, 10)
    }

    @After
    fun closeDb() = db.close()

    private fun insert(id: Long, note: String?, url: String = "https://h/$id") {
        db.prepareStatement(
            "INSERT INTO downloads (id, url, hosterId, packageId, fileName, archiveKey, fileSize, downloadedBytes, " +
                "speedBps, status, errorMessage, localPath, attempts, retryAt, online, extractProgress, addedAt) " +
                "VALUES (?, ?, 'ddownload', 1, 'film.part1.rar', 'film', 4096, 2048, 0, 'COMPLETED', ?, '/x/film.part1.rar', 2, 7, 1, 55, ?)"
        ).use {
            it.setLong(1, id); it.setString(2, url); it.setString(3, note); it.setLong(4, id * 10)
            it.executeUpdate()
        }
    }

    private fun migrate() = AppDatabase.MIGRATION_10_11.migrate(MigrationSql.roomDb(db))

    private fun note(id: Long): String? =
        db.prepareStatement("SELECT errorMessage FROM downloads WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }

    @Test
    fun `alte Vermerktexte werden zu Codes, andere Vermerke bleiben`() {
        insert(1, DownloadNotes.LEGACY_WAITING_PARTS)
        insert(2, DownloadNotes.LEGACY_WAITING_WIFI)
        insert(3, "Fehler – Versuch 2/5 in 20s")
        insert(4, null)
        insert(5, "FREE_WAIT|Rapidgator: Tageslimit")
        migrate()
        assertEquals(DownloadNotes.WAITING_PARTS, note(1))
        assertEquals(DownloadNotes.WAITING_WIFI, note(2))
        assertEquals("Fehler – Versuch 2/5 in 20s", note(3))
        assertNull(note(4))
        assertEquals("FREE_WAIT|Rapidgator: Tageslimit", note(5))
    }

    @Test
    fun `extractProgress faellt weg, alle anderen Spalten und Werte bleiben`() {
        insert(1, null)
        migrate()
        val migrated = MigrationSql.columns(db, "downloads")
        assertTrue(migrated.toString(), "extractProgress" !in migrated)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            MigrationSql.createSchema(fresh, 11)
            assertEquals(MigrationSql.columns(fresh, "downloads"), migrated)
            assertEquals(indexes(fresh), indexes(db))
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT * FROM downloads WHERE id = 1").use { rs ->
                rs.next()
                assertEquals("https://h/1", rs.getString("url"))
                assertEquals("film.part1.rar", rs.getString("fileName"))
                assertEquals("film", rs.getString("archiveKey"))
                assertEquals(4096L, rs.getLong("fileSize"))
                assertEquals(2048L, rs.getLong("downloadedBytes"))
                assertEquals("/x/film.part1.rar", rs.getString("localPath"))
                assertEquals(2, rs.getInt("attempts"))
                assertEquals(7L, rs.getLong("retryAt"))
                assertEquals(1, rs.getInt("online"))
                assertEquals(10L, rs.getLong("addedAt"))
            }
        }
    }

    @Test
    fun `eindeutige Adresse bleibt nach der Migration erzwungen`() {
        insert(1, null, url = "https://h/x")
        migrate()
        assertThrows(SQLException::class.java) { insert(2, null, url = "https://h/x") }
    }

    private companion object {
        fun indexes(conn: Connection): Set<String> =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(downloads)").use { rs ->
                    generateSequence { if (rs.next()) rs.getString("name") + ":" + rs.getInt("unique") else null }
                        .filter { !it.startsWith("sqlite_autoindex") }.toSet()
                }
            }
    }
}
