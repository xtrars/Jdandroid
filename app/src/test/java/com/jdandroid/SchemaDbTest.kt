package com.jdandroid

import com.jdandroid.core.ArchiveNames
import com.jdandroid.data.DownloadStatus
import org.junit.After
import org.junit.Before
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * Grundlage fuer JVM-Tests der DAO-Abfragen: eine echte SQLite-Datenbank mit
 * dem exportierten Room-Schema (app/schemas, hoechste Version - wird die
 * Datenbank erhoeht, laufen die Tests automatisch gegen das neue Schema).
 * Zwei Pakete (1 "A", 2 "B") liegen bereit; [item] legt Eintraege wie die
 * App an, archiveKey aus dem Dateinamen abgeleitet.
 */
abstract class SchemaDbTest {

    protected lateinit var db: Connection

    @Before
    fun openDb() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        val schema = latestSchema().readText()
        // Alle CREATE-Anweisungen des Schemas (Tabellen und Indizes); jede
        // Anweisung gehoert zur zuletzt genannten Tabelle
        var table = ""
        Regex(""""(tableName|createSql)":\s*"([^"]+)"""").findAll(schema).forEach { m ->
            when (m.groupValues[1]) {
                "tableName" -> table = m.groupValues[2]
                else -> db.createStatement().use {
                    it.execute(m.groupValues[2].replace("\${TABLE_NAME}", table))
                }
            }
        }
        db.createStatement().use { it.execute("INSERT INTO packages (id, name, autoNamed, addedAt) VALUES (1, 'A', 1, 1)") }
        db.createStatement().use { it.execute("INSERT INTO packages (id, name, autoNamed, addedAt) VALUES (2, 'B', 1, 1)") }
    }

    @After
    fun closeDb() = db.close()

    protected fun item(
        id: Long, name: String?, status: DownloadStatus, packageId: Long? = 1, note: String? = null,
        downloadedBytes: Long = 0
    ) {
        bind(
            "INSERT INTO downloads (id, url, hosterId, packageId, fileName, archiveKey, fileSize, downloadedBytes, " +
                "speedBps, status, errorMessage, attempts, retryAt, online, extractProgress, addedAt) VALUES " +
                "(:id, :url, 'h', :packageId, :fileName, :archiveKey, -1, :downloadedBytes, 0, :status, :note, 0, 0, 0, -1, :id)",
            "id" to id, "url" to "https://h/$id", "packageId" to packageId, "fileName" to name,
            "archiveKey" to ArchiveNames.archiveKey(name), "status" to status.name, "note" to note,
            "downloadedBytes" to downloadedBytes
        ).use { it.executeUpdate() }
    }

    /** Benannte Parameter (:name) wie in Room binden: Nummer nach erstem Auftreten. */
    protected fun bind(sql: String, vararg params: Pair<String, Any?>): PreparedStatement {
        val values = params.toMap()
        val names = Regex(""":(\w+)""").findAll(sql).map { it.groupValues[1] }.distinct().toList()
        val statement = db.prepareStatement(sql)
        names.forEachIndexed { i, n -> statement.setObject(i + 1, values.getValue(n)) }
        return statement
    }

    protected fun execute(sql: String, vararg params: Pair<String, Any?>): Int =
        bind(sql, *params).use { it.executeUpdate() }

    protected fun count(sql: String, vararg params: Pair<String, Any?>): Int =
        bind(sql, *params).use { st -> st.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }

    protected fun long(sql: String, vararg params: Pair<String, Any?>): Long =
        bind(sql, *params).use { st -> st.executeQuery().use { rs -> rs.next(); rs.getLong(1) } }

    protected fun ids(sql: String, vararg params: Pair<String, Any?>): List<Long> =
        bind(sql, *params).use { st ->
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong("id") else null }.toList() }
        }

    /** Eine Spalte eines Eintrags als Text (NULL -> null). */
    protected fun column(id: Long, column: String): String? =
        bind("SELECT $column FROM downloads WHERE id = :id", "id" to id).use { st ->
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }

    private companion object {
        /** Hoechste N.json unter app/schemas - numerisch sortiert, nicht als Text. */
        fun latestSchema(): File =
            listOf("schemas", "app/schemas")
                .map { File(it, "com.jdandroid.data.AppDatabase") }
                .first { it.isDirectory }
                .listFiles { f -> f.name.endsWith(".json") && f.length() > 0 }!!
                .maxByOrNull { it.nameWithoutExtension.toInt() }!!
    }
}
