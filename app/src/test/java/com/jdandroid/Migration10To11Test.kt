package com.jdandroid

import androidx.sqlite.db.SupportSQLiteDatabase
import com.jdandroid.data.AppDatabase
import com.jdandroid.data.DownloadNotes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Runs the real Room migration 10 -> 11 against SQLite on the JVM: a schema-10
 * database is built from the exported schema and the migration's SQL is fed
 * through a [SupportSQLiteDatabase] stand-in.
 */
class Migration10To11Test {

    private lateinit var db: Connection

    @Before
    fun openDb() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        createSchema(db, 10)
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

    private fun migrate() {
        val fake = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name != "execSQL") throw UnsupportedOperationException(method.name)
            val bind = (args.getOrNull(1) as? Array<*>).orEmpty()
            db.prepareStatement(args[0] as String).use { st ->
                bind.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                st.execute()
            }
            null
        } as SupportSQLiteDatabase
        AppDatabase.MIGRATION_10_11.migrate(fake)
    }

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
        val migrated = columns(db)
        assertTrue(migrated.toString(), "extractProgress" !in migrated)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            createSchema(fresh, 11)
            assertEquals(columns(fresh), migrated)
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
        fun schemaFile(version: Int): File =
            listOf("schemas", "app/schemas")
                .map { File(it, "com.jdandroid.data.AppDatabase/$version.json") }
                .first { it.isFile }

        /** Executes every createSql of the exported schema like [SchemaDbTest]. */
        fun createSchema(conn: Connection, version: Int) {
            var table = ""
            Regex(""""(tableName|createSql)":\s*"([^"]+)"""").findAll(schemaFile(version).readText()).forEach { m ->
                when (m.groupValues[1]) {
                    "tableName" -> table = m.groupValues[2]
                    else -> conn.createStatement().use { it.execute(m.groupValues[2].replace("\${TABLE_NAME}", table)) }
                }
            }
        }

        fun columns(conn: Connection): List<String> =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(downloads)").use { rs ->
                    generateSequence { if (rs.next()) rs.getString("name") else null }.toList()
                }
            }

        fun indexes(conn: Connection): Set<String> =
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA index_list(downloads)").use { rs ->
                    generateSequence { if (rs.next()) rs.getString("name") + ":" + rs.getInt("unique") else null }
                        .filter { !it.startsWith("sqlite_autoindex") }.toSet()
                }
            }
    }
}
