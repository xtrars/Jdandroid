package com.jdandroid.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import com.jdandroid.core.ArchiveNames
import kotlinx.coroutines.flow.Flow

/** COLLECTED = sitting in the link grabber, not started yet. */
enum class DownloadStatus { COLLECTED, QUEUED, RUNNING, PAUSED, EXTRACTING, COMPLETED, FAILED, OFFLINE }

/** Result of the online check in the link grabber. */
object OnlineState {
    const val UNKNOWN = 0
    const val ONLINE = 1
    const val OFFLINE = 2
    const val CHECKING = 3
}

/** Bundles links that were added together. */
@Entity(tableName = "packages")
data class DownloadPackage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Auto-named packages may later be renamed from the file names. */
    val autoNamed: Boolean = true,
    /** Origin, e.g. the web page for Click'n'Load. */
    val source: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["url"], unique = true), Index(value = ["archiveKey"]),
        Index(value = ["status"]), Index(value = ["packageId"])
    ]
)
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val hosterId: String,
    val packageId: Long? = null,
    val fileName: String? = null,
    /**
     * Archive base name ([ArchiveNames.archiveKey] of [fileName]), null = no
     * archive or name unknown. Written wherever the name is set; archive sets
     * are queried through it.
     */
    val archiveKey: String? = null,
    val fileSize: Long = -1,
    val downloadedBytes: Long = 0,
    val speedBps: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val localPath: String? = null,
    val attempts: Int = 0,
    /** Earliest time for the next attempt (backoff). */
    val retryAt: Long = 0,
    /** See [OnlineState]; the related note is in [errorMessage]. */
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
    /** Session cookies from the browser login (name=value; ...). */
    val cookies: String? = null,
    val premiumUntil: Long = 0,
    /** Remaining traffic in bytes, -1 = unknown. */
    val trafficLeft: Long = -1,
    /** Total quota in bytes, -1 = unknown. */
    val trafficTotal: Long = -1,
    val trafficUnlimited: Boolean = false,
    val valid: Boolean = false,
    val lastChecked: Long = 0,
    val statusText: String? = null
)

/**
 * Premium: expiry in the future or, without a date, a premium status text
 * (ddownload "Premium"/"Ultimate", 1fichier "Premium/Access"). A valid
 * account without premium is a free account and the engine uses free mode.
 */
fun Account.hasPremium(now: Long = System.currentTimeMillis()): Boolean =
    valid && (
        premiumUntil > now ||
            (premiumUntil == 0L && statusText?.let { it.startsWith("Premium") || it.startsWith("Ultimate") } == true)
        )

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadItem?

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadItem>

    /** Next queued entry not already in [running]; otherwise a download could start twice after requeueRunning(). */
    @Query(
        "SELECT * FROM downloads WHERE status = 'QUEUED' AND retryAt <= :now " +
            "AND id NOT IN (:running) ORDER BY addedAt ASC LIMIT 1"
    )
    suspend fun nextQueued(now: Long, running: List<Long>): DownloadItem?

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED', 'RUNNING', 'EXTRACTING')")
    suspend fun openCount(): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'QUEUED'")
    suspend fun queuedCount(): Int

    /** Queued entries starting by themselves before [before]; captcha waiters (far-future retryAt) do not keep the service alive. */
    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'QUEUED' AND retryAt <= :before")
    suspend fun queuedCountDue(before: Long): Int

    @Query(DownloadQueries.NEXT_RETRY_AT)
    suspend fun nextRetryAt(now: Long, horizon: Long): Long?

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'PAUSED'")
    suspend fun pausedCount(): Int

    /** Notification progress sum; [except] are the entries whose live value comes from the ProgressBus. */
    @Query(DownloadQueries.OPEN_DOWNLOADED_BYTES_EXCEPT)
    suspend fun openDownloadedBytesExcept(except: List<Long>): Long

    @Query(
        "SELECT COALESCE(SUM(fileSize), 0) FROM downloads " +
            "WHERE status IN ('RUNNING', 'QUEUED', 'PAUSED') AND fileSize > 0"
    )
    suspend fun openTotalBytes(): Long

    @Query("UPDATE downloads SET status = 'QUEUED', errorMessage = NULL WHERE status = 'PAUSED'")
    suspend fun requeuePaused()

    /** "Pause all" must include queued entries, otherwise pump() starts the next ones at once. */
    @Query("UPDATE downloads SET status = 'PAUSED' WHERE status = 'QUEUED'")
    suspend fun pauseQueued()

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

    @Query(DownloadQueries.APPLY_CHECK)
    suspend fun applyCheck(
        id: Long, online: Int, note: String?, fileName: String?, archiveKey: String?, fileSize: Long
    )

    @Query("DELETE FROM downloads WHERE status = 'COLLECTED' AND online = 2")
    suspend fun deleteOfflineCollected()

    @Query("SELECT COUNT(*) FROM downloads WHERE url = :url")
    suspend fun countByUrl(url: String): Int

    /** Returns -1 when the URL already exists (unique index). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: DownloadItem): Long

    @Update
    suspend fun update(item: DownloadItem)

    @Query("UPDATE downloads SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, error: String? = null)

    /** Persisted at transfer start, at most every 30 s and on leaving the transfer; speed is a live ProgressBus value only. */
    @Query("UPDATE downloads SET downloadedBytes = :bytes, fileSize = :total WHERE id = :id")
    suspend fun saveProgress(id: Long, bytes: Long, total: Long)

    /** After a process restart: re-queue running and extracting entries. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, speedBps = 0 " +
            "WHERE status IN ('RUNNING', 'EXTRACTING')"
    )
    suspend fun requeueRunning()

    /** [requeueRunning] without the entries still being extracted in this process. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, speedBps = 0 " +
            "WHERE status IN ('RUNNING', 'EXTRACTING') AND id NOT IN (:except)"
    )
    suspend fun requeueRunningExcept(except: List<Long>)

    @Query(
        "UPDATE downloads SET status = 'EXTRACTING', errorMessage = NULL, speedBps = 0 " +
            "WHERE id IN (:ids) AND status IN ('COMPLETED', 'EXTRACTING', 'RUNNING')"
    )
    suspend fun setExtractingSet(ids: List<Long>)

    @Query(
        "UPDATE downloads SET status = 'COMPLETED', localPath = :path, errorMessage = :note, " +
            "attempts = 0, retryAt = 0 WHERE id IN (:ids) AND status = 'EXTRACTING'"
    )
    suspend fun completeExtractingSet(ids: List<Long>, path: String?, note: String?)

    @Query("UPDATE downloads SET status = 'PAUSED', speedBps = 0 WHERE id = :id AND status IN ('RUNNING', 'QUEUED')")
    suspend fun pauseIfActive(id: Long)

    /** [pauseIfActive] for every entry of a package in one statement. */
    @Query("UPDATE downloads SET status = 'PAUSED', speedBps = 0 WHERE packageId = :packageId AND status IN ('RUNNING', 'QUEUED')")
    suspend fun pauseActiveInPackage(packageId: Long)

    /** "Wi-Fi only": send a running download back to the queue. */
    @Query(DownloadQueries.REQUEUE_IF_RUNNING)
    suspend fun requeueIfRunning(id: Long)

    /** Completes only if the entry was not paused or deleted meanwhile. */
    @Query(
        "UPDATE downloads SET status = 'COMPLETED', localPath = :path, errorMessage = :note, " +
            "speedBps = 0, attempts = 0, retryAt = 0 " +
            "WHERE id = :id AND status IN ('RUNNING', 'EXTRACTING')"
    )
    suspend fun completeIfActive(id: Long, path: String?, note: String?): Int

    @Query("UPDATE downloads SET localPath = :path, errorMessage = NULL WHERE id IN (:ids) AND status = 'COMPLETED'")
    suspend fun updateCompletedSet(ids: List<Long>, path: String?)

    /** Resets link checks left in CHECKING by a process exit. */
    @Query("UPDATE downloads SET online = 0 WHERE online = 3")
    suspend fun resetChecking()

    @Query(
        "UPDATE downloads SET status = 'QUEUED', attempts = :attempts, retryAt = :retryAt, " +
            "errorMessage = :error, speedBps = 0 WHERE id = :id"
    )
    suspend fun scheduleRetry(id: Long, attempts: Int, retryAt: Long, error: String?)

    /** Name and archive key belong together, see [renameFile]. */
    @Query("UPDATE downloads SET fileName = :name, archiveKey = :archiveKey WHERE id = :id")
    suspend fun setFileName(id: Long, name: String, archiveKey: String?)

    @Query(ArchiveSets.PENDING_ACTIVE)
    suspend fun pendingActiveParts(packageId: Long?, key: String, selfId: Long): Int

    @Query(ArchiveSets.PENDING_LOADING)
    suspend fun pendingLoadingParts(packageId: Long?, key: String, selfId: Long): Int

    @Query(ArchiveSets.SET_IDS)
    suspend fun archiveSetIds(packageId: Long?, key: String, selfId: Long): List<Long>

    @Query(ArchiveSets.COMPLETED_PARTS)
    suspend fun completedParts(packageId: Long?, key: String): List<DownloadItem>

    @Query(ArchiveSets.DELETE_EXTRACTED)
    suspend fun deleteExtractedSet(packageId: Long?, key: String, selfId: Long)

    @Query(ArchiveSets.WAITING_PARTS)
    suspend fun waitingParts(packageId: Long, note: String): List<DownloadItem>

    @Query(ArchiveSets.COMPLETED_ARCHIVES)
    suspend fun completedArchives(packageId: Long): List<DownloadItem>

    @Query(ArchiveSets.COUNT_KEY)
    suspend fun countByArchiveKey(packageId: Long?, key: String): Int

    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attempts = 0, retryAt = 0, " +
            "downloadedBytes = 0, localPath = NULL WHERE id = :id AND status = 'COMPLETED'"
    )
    suspend fun requeueCompleted(id: Long)

    /** After real progress the attempt counter starts over. */
    @Query("UPDATE downloads SET attempts = 0 WHERE id = :id")
    suspend fun resetAttempts(id: Long)

    /** Manual restart, never for running entries (would start twice). */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attempts = 0, " +
            "retryAt = 0 WHERE id = :id AND status IN ('PAUSED', 'FAILED', 'OFFLINE')"
    )
    suspend fun requeue(id: Long)

    /** Releases a waiting entry at once (captcha solved); a paused or running entry stays untouched. */
    @Query(
        "UPDATE downloads SET retryAt = 0, errorMessage = NULL, speedBps = 0 " +
            "WHERE id = :id AND status = 'QUEUED'"
    )
    suspend fun releaseQueued(id: Long)

    @Query(DownloadQueries.REQUEUE_PAUSED_AND_FAILED)
    suspend fun requeuePausedAndFailed()

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("SELECT * FROM downloads WHERE packageId = :packageId")
    suspend fun byPackage(packageId: Long): List<DownloadItem>

    @Query("DELETE FROM downloads WHERE packageId = :packageId")
    suspend fun deletePackageItems(packageId: Long)
}

/** Sets the file name and derives the archive key from it. */
suspend fun DownloadDao.renameFile(id: Long, name: String) =
    setFileName(id, name, ArchiveNames.archiveKey(name))

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
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun accountDao(): AccountDao
    abstract fun packageDao(): PackageDao

    companion object {
        /**
         * Versions 1 to 4 (early development states) are rebuilt instead of
         * migrated (fallbackToDestructiveMigrationFrom in [com.jdandroid.JdApp]).
         * From version 5 on accounts and the download list survive updates.
         */
        val DESTRUCTIVE_FROM = intArrayOf(1, 2, 3, 4)

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

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove duplicate URLs (parallel Click'n'Load requests) before
                // creating the unique index.
                db.execSQL("DELETE FROM downloads WHERE id NOT IN (SELECT MIN(id) FROM downloads GROUP BY url)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_downloads_url ON downloads(url)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN extractProgress INTEGER NOT NULL DEFAULT -1")
            }
        }

        /** Adds the archiveKey column and back-fills it from existing file names. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN archiveKey TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_archiveKey ON downloads(archiveKey)")
                // Read all keys first: never write to the table the cursor is
                // iterating over.
                val keys = ArrayList<Pair<Long, String>>()
                db.query("SELECT id, fileName FROM downloads WHERE fileName IS NOT NULL").use { c ->
                    while (c.moveToNext()) {
                        ArchiveNames.archiveKey(c.getString(1))?.let { keys += c.getLong(0) to it }
                    }
                }
                keys.forEach { (id, key) ->
                    db.execSQL("UPDATE downloads SET archiveKey = ? WHERE id = ?", arrayOf<Any>(key, id))
                }
            }
        }

        /**
         * Drops extractProgress (the state now lives in
         * [com.jdandroid.core.ProgressBus]). SQLite cannot drop a column, so
         * the table is rebuilt; indices on status and packageId are added. The
         * CREATE statements must match exported schema 11 exactly. Stored
         * German notes are then converted to codes (see [DownloadNotes]).
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads_neu` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, " +
                        "`hosterId` TEXT NOT NULL, `packageId` INTEGER, `fileName` TEXT, `archiveKey` TEXT, " +
                        "`fileSize` INTEGER NOT NULL, `downloadedBytes` INTEGER NOT NULL, " +
                        "`speedBps` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorMessage` TEXT, " +
                        "`localPath` TEXT, `attempts` INTEGER NOT NULL, `retryAt` INTEGER NOT NULL, " +
                        "`online` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL)"
                )
                val spalten = "`id`, `url`, `hosterId`, `packageId`, `fileName`, `archiveKey`, `fileSize`, " +
                    "`downloadedBytes`, `speedBps`, `status`, `errorMessage`, `localPath`, `attempts`, " +
                    "`retryAt`, `online`, `addedAt`"
                db.execSQL("INSERT INTO `downloads_neu` ($spalten) SELECT $spalten FROM `downloads`")
                db.execSQL("DROP TABLE `downloads`")
                db.execSQL("ALTER TABLE `downloads_neu` RENAME TO `downloads`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_url` ON `downloads` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_archiveKey` ON `downloads` (`archiveKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_status` ON `downloads` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_packageId` ON `downloads` (`packageId`)")
                db.execSQL(
                    "UPDATE downloads SET errorMessage = ? WHERE errorMessage = ?",
                    arrayOf<Any>(DownloadNotes.WAITING_PARTS, DownloadNotes.LEGACY_WAITING_PARTS)
                )
                db.execSQL(
                    "UPDATE downloads SET errorMessage = ? WHERE errorMessage = ?",
                    arrayOf<Any>(DownloadNotes.WAITING_WIFI, DownloadNotes.LEGACY_WAITING_WIFI)
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
        )
    }
}
