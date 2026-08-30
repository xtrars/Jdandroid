package com.jdandroid.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, EXTRACTING, COMPLETED, FAILED, OFFLINE }

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val hosterId: String,
    val fileName: String? = null,
    val fileSize: Long = -1,
    val downloadedBytes: Long = 0,
    val speedBps: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val localPath: String? = null,
    /** Bisherige automatische Wiederholversuche. */
    val attempts: Int = 0,
    /** Fruehester Zeitpunkt fuer den naechsten Versuch (Backoff). */
    val retryAt: Long = 0,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hosterId: String,
    val username: String? = null,
    val password: String? = null,
    val apiKey: String? = null,
    /** Session-Cookies aus dem Browser-Login (Name=Wert; ...), falls genutzt. */
    val cookies: String? = null,
    val premiumUntil: Long = 0,
    val trafficLeft: Long = -1,
    val valid: Boolean = false,
    val lastChecked: Long = 0,
    val statusText: String? = null
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadItem?

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadItem>

    @Query(
        "SELECT * FROM downloads WHERE status = 'QUEUED' AND retryAt <= :now " +
            "ORDER BY addedAt ASC LIMIT 1"
    )
    suspend fun nextQueued(now: Long = System.currentTimeMillis()): DownloadItem?

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'QUEUED'")
    suspend fun queuedCount(): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE url = :url")
    suspend fun countByUrl(url: String): Int

    @Insert
    suspend fun insert(item: DownloadItem): Long

    @Update
    suspend fun update(item: DownloadItem)

    @Query("UPDATE downloads SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, error: String? = null)

    @Query("UPDATE downloads SET downloadedBytes = :bytes, fileSize = :total, speedBps = :speed WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, total: Long, speed: Long)

    @Query("UPDATE downloads SET status = 'QUEUED', errorMessage = NULL WHERE status = 'RUNNING'")
    suspend fun requeueRunning()

    /** Automatischer Wiederholversuch mit Backoff. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', attempts = :attempts, retryAt = :retryAt, " +
            "errorMessage = :error, speedBps = 0 WHERE id = :id"
    )
    suspend fun scheduleRetry(id: Long, attempts: Int, retryAt: Long, error: String?)

    /** Manueller Neustart: Zaehler und Wartezeit zuruecksetzen. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attempts = 0, " +
            "retryAt = 0 WHERE id = :id"
    )
    suspend fun requeue(id: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY hosterId")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE hosterId = :hosterId AND valid = 1 LIMIT 1")
    suspend fun validForHoster(hosterId: String): Account?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): Account?

    @Insert
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)
}

@Database(entities = [DownloadItem::class, Account::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun accountDao(): AccountDao

    companion object {
        /**
         * Echte Migrationen statt destruktivem Neuaufbau: bei einem Update
         * bleiben Konten und Downloadliste erhalten.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN cookies TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN retryAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
