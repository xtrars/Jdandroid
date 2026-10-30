package com.jdandroid

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection

/** Runs Room migrations on the JVM against SQLite databases built from the exported schemas. */
internal object MigrationSql {

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

    fun columns(conn: Connection, table: String): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.toList()
            }
        }

    /** A [SupportSQLiteDatabase] stand-in that only feeds execSQL through to [conn]. */
    fun roomDb(conn: Connection): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name != "execSQL") throw UnsupportedOperationException(method.name)
            val bind = (args.getOrNull(1) as? Array<*>).orEmpty()
            conn.prepareStatement(args[0] as String).use { st ->
                bind.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                st.execute()
            }
            null
        } as SupportSQLiteDatabase
}
