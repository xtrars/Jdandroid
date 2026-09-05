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
 * Base for JVM tests of DAO queries: a real SQLite database with the latest
 * exported Room schema (app/schemas). Two packages (1 "A", 2 "B") exist;
 * [item] creates entries like the app does, archiveKey derived from the name.
 */
abstract class SchemaDbTest {

    protected lateinit var db: Connection

    @Before
    fun openDb() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        val schema = latestSchema().readText()
        // Every CREATE statement belongs to the most recently named table
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
        downloadedBytes: Long = 0, addedAt: Long = id
    ) {
        bind(
            "INSERT INTO downloads (id, url, hosterId, packageId, fileName, archiveKey, fileSize, downloadedBytes, " +
                "speedBps, status, errorMessage, attempts, retryAt, online, addedAt) VALUES " +
                "(:id, :url, 'h', :packageId, :fileName, :archiveKey, -1, :downloadedBytes, 0, :status, :note, 0, 0, 0, :addedAt)",
            "id" to id, "url" to "https://h/$id", "packageId" to packageId, "fileName" to name,
            "archiveKey" to ArchiveNames.archiveKey(name), "status" to status.name, "note" to note,
            "downloadedBytes" to downloadedBytes, "addedAt" to addedAt
        ).use { it.executeUpdate() }
    }

    /** Binds named parameters (:name) like Room: numbered by first occurrence. */
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

    protected fun column(id: Long, column: String): String? =
        bind("SELECT $column FROM downloads WHERE id = :id", "id" to id).use { st ->
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }

    private companion object {
        /** Highest N.json under app/schemas, sorted numerically. */
        fun latestSchema(): File =
            listOf("schemas", "app/schemas")
                .map { File(it, "com.jdandroid.data.AppDatabase") }
                .first { it.isDirectory }
                .listFiles { f -> f.name.endsWith(".json") && f.length() > 0 }!!
                .maxByOrNull { it.nameWithoutExtension.toInt() }!!
    }
}
