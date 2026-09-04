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

/** COLLECTED = liegt im Linksammler und wurde noch nicht gestartet (wie im JDownloader). */
enum class DownloadStatus { COLLECTED, QUEUED, RUNNING, PAUSED, EXTRACTING, COMPLETED, FAILED, OFFLINE }

/** Ergebnis der Online-Pruefung im Linksammler. */
object OnlineState {
    const val UNKNOWN = 0
    const val ONLINE = 1
    const val OFFLINE = 2
    const val CHECKING = 3
}

/** Paket wie im JDownloader: buendelt zusammen hinzugefuegte Links. */
@Entity(tableName = "packages")
data class DownloadPackage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Automatisch benannt: darf spaeter aus den Dateinamen verfeinert werden. */
    val autoNamed: Boolean = true,
    /** Herkunft, z.B. die Webseite bei Click'n'Load - sichtbar in der Liste. */
    val source: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val hosterId: String,
    val packageId: Long? = null,
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
    /** Online-Pruefung (siehe [OnlineState]); Hinweis dazu in [errorMessage]. */
    val online: Int = OnlineState.UNKNOWN,
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
    /** Verbleibender Traffic in Byte, -1 = unbekannt. */
    val trafficLeft: Long = -1,
    /** Gesamtkontingent in Byte, -1 = unbekannt. */
    val trafficTotal: Long = -1,
    /** Hoster ohne Traffic-Limit. */
    val trafficUnlimited: Boolean = false,
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

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'PAUSED'")
    suspend fun pausedCount(): Int

    /** Summen fuer die Fortschrittsanzeige in der Benachrichtigung. */
    @Query(
        "SELECT COALESCE(SUM(downloadedBytes), 0) FROM downloads " +
            "WHERE status IN ('RUNNING', 'QUEUED', 'PAUSED')"
    )
    suspend fun openDownloadedBytes(): Long

    @Query(
        "SELECT COALESCE(SUM(fileSize), 0) FROM downloads " +
            "WHERE status IN ('RUNNING', 'QUEUED', 'PAUSED') AND fileSize > 0"
    )
    suspend fun openTotalBytes(): Long

    @Query("UPDATE downloads SET status = 'QUEUED', errorMessage = NULL WHERE status = 'PAUSED'")
    suspend fun requeuePaused()

    /** "Alle pausieren": auch Wartende anhalten, sonst startet pump() sofort die naechsten. */
    @Query("UPDATE downloads SET status = 'PAUSED' WHERE status = 'QUEUED'")
    suspend fun pauseQueued()

    /** Linksammler: Paket starten bzw. alles starten. */
    @Query("UPDATE downloads SET status = 'QUEUED', errorMessage = NULL WHERE status = 'COLLECTED' AND packageId = :packageId")
    suspend fun startCollected(packageId: Long)

    @Query("UPDATE downloads SET status = 'QUEUED', errorMessage = NULL WHERE status = 'COLLECTED'")
    suspend fun startAllCollected()

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'COLLECTED'")
    suspend fun collectedCount(): Int

    @Query("SELECT * FROM downloads WHERE status = 'COLLECTED' AND online IN (:states)")
    suspend fun collectedWithOnline(states: List<Int>): List<DownloadItem>

    @Query("UPDATE downloads SET online = :online WHERE id = :id AND status = 'COLLECTED'")
    suspend fun setOnline(id: Long, online: Int)

    /** Ergebnis der Online-Pruefung eintragen (Name/Groesse nur, wenn bekannt). */
    @Query(
        "UPDATE downloads SET online = :online, errorMessage = :note, " +
            "fileName = COALESCE(:fileName, fileName), " +
            "fileSize = CASE WHEN :fileSize > 0 THEN :fileSize ELSE fileSize END " +
            "WHERE id = :id AND status = 'COLLECTED'"
    )
    suspend fun applyCheck(id: Long, online: Int, note: String?, fileName: String?, fileSize: Long)

    @Query("DELETE FROM downloads WHERE status = 'COLLECTED' AND online = 2")
    suspend fun deleteOfflineCollected()

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

    @Query("SELECT * FROM downloads WHERE packageId = :packageId")
    suspend fun byPackage(packageId: Long): List<DownloadItem>

    @Query("DELETE FROM downloads WHERE packageId = :packageId")
    suspend fun deletePackageItems(packageId: Long)
}

@Dao
interface PackageDao {
    @Query("SELECT * FROM packages ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DownloadPackage>>

    @Query("SELECT * FROM packages WHERE id = :id")
    suspend fun byId(id: Long): DownloadPackage?

    @Insert
    suspend fun insert(pkg: DownloadPackage): Long

    @Query("UPDATE packages SET name = :name, autoNamed = 0 WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE packages SET name = :name WHERE id = :id AND autoNamed = 1")
    suspend fun refineAutoName(id: Long, name: String)

    @Query("DELETE FROM packages WHERE id = :id")
    suspend fun delete(id: Long)

    /** Leere Pakete aufraeumen. */
    @Query("DELETE FROM packages WHERE id NOT IN (SELECT DISTINCT packageId FROM downloads WHERE packageId IS NOT NULL)")
    suspend fun deleteEmpty()
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY hosterId")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE hosterId = :hosterId AND valid = 1 LIMIT 1")
    suspend fun validForHoster(hosterId: String): Account?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): Account?

    @Query("SELECT * FROM accounts")
    suspend fun all(): List<Account>

    @Query("SELECT * FROM accounts WHERE hosterId = :hosterId")
    suspend fun byHoster(hosterId: String): List<Account>

    @Insert
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)
}

@Database(
    entities = [DownloadItem::class, Account::class, DownloadPackage::class],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun accountDao(): AccountDao
    abstract fun packageDao(): PackageDao

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS packages (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "autoNamed INTEGER NOT NULL DEFAULT 1, " +
                        "addedAt INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE downloads ADD COLUMN packageId INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE packages ADD COLUMN source TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN online INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN trafficTotal INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE accounts ADD COLUMN trafficUnlimited INTEGER NOT NULL DEFAULT 0")
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
        )
    }
}
