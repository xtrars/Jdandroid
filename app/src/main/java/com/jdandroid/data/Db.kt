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

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["url"], unique = true), Index(value = ["archiveKey"]),
        // Warteschlange und Zaehler filtern nach status, Pakete nach packageId
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
     * Basisname des Archivs ([ArchiveNames.archiveKey] aus [fileName]),
     * null = kein Archiv oder Name unbekannt. Wird ueberall mitgeschrieben,
     * wo der Name gesetzt wird; Archiv-Sets werden darueber abgefragt.
     */
    val archiveKey: String? = null,
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

/**
 * Konto hat Premium: Ablaufdatum in der Zukunft oder - ohne Datum - ein
 * Premium-Kontostatus (ddownload: "Premium"/"Ultimate", 1fichier:
 * "Premium/Access"). Ein gueltiges Konto ohne Premium ist ein Free-Konto:
 * die Engine laedt dann im Free-Modus, nicht ueber den Premium-Weg.
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

    /**
     * Naechster wartender Eintrag, der nicht bereits laeuft ([running]): sonst
     * koennte derselbe Download nach requeueRunning() ein zweites Mal starten.
     */
    @Query(
        "SELECT * FROM downloads WHERE status = 'QUEUED' AND retryAt <= :now " +
            "AND id NOT IN (:running) ORDER BY addedAt ASC LIMIT 1"
    )
    suspend fun nextQueued(now: Long, running: List<Long>): DownloadItem?

    /** Offene Arbeit (wartend, laufend, entpackend) - fuer den App-Start. */
    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED', 'RUNNING', 'EXTRACTING')")
    suspend fun openCount(): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'QUEUED'")
    suspend fun queuedCount(): Int

    /**
     * Wartende Eintraege, die bis [before] von selbst starten. Eintraege, die
     * auf ein Captcha warten (retryAt weit in der Zukunft), zaehlen nicht:
     * sie halten den Dienst nicht am Leben.
     */
    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'QUEUED' AND retryAt <= :before")
    suspend fun queuedCountDue(before: Long): Int

    /** Naechstes retryAt in der Zukunft bis [horizon], null ohne (siehe [DownloadQueries.NEXT_RETRY_AT]). */
    @Query(DownloadQueries.NEXT_RETRY_AT)
    suspend fun nextRetryAt(now: Long, horizon: Long): Long?

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'PAUSED'")
    suspend fun pausedCount(): Int

    /**
     * Summen fuer die Fortschrittsanzeige in der Benachrichtigung. [except]
     * sind die Eintraege mit Live-Stand im ProgressBus; ihr Datenbankwert
     * ist bis zu 30 s alt und wird von der Engine durch den Live-Wert ersetzt
     * (siehe [DownloadQueries.OPEN_DOWNLOADED_BYTES_EXCEPT]).
     */
    @Query(DownloadQueries.OPEN_DOWNLOADED_BYTES_EXCEPT)
    suspend fun openDownloadedBytesExcept(except: List<Long>): Long

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

    /** Ergebnis der Online-Pruefung eintragen (siehe [DownloadQueries.APPLY_CHECK]). */
    @Query(DownloadQueries.APPLY_CHECK)
    suspend fun applyCheck(
        id: Long, online: Int, note: String?, fileName: String?, archiveKey: String?, fileSize: Long
    )

    @Query("DELETE FROM downloads WHERE status = 'COLLECTED' AND online = 2")
    suspend fun deleteOfflineCollected()

    @Query("SELECT COUNT(*) FROM downloads WHERE url = :url")
    suspend fun countByUrl(url: String): Int

    /** Liefert -1, wenn die URL bereits vorhanden ist (eindeutiger Index). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: DownloadItem): Long

    @Update
    suspend fun update(item: DownloadItem)

    @Query("UPDATE downloads SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, error: String? = null)

    /**
     * Bytestand sichern: beim Start der Uebertragung, hoechstens alle 30 s und
     * beim Verlassen der Uebertragung. Geschwindigkeit wird hier nicht
     * geschrieben - sie ist ein Live-Wert des ProgressBus.
     */
    @Query("UPDATE downloads SET downloadedBytes = :bytes, fileSize = :total WHERE id = :id")
    suspend fun saveProgress(id: Long, bytes: Long, total: Long)

    /** Nach Prozess-Ende: laufende und entpackende Eintraege wieder einreihen. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, speedBps = 0 " +
            "WHERE status IN ('RUNNING', 'EXTRACTING')"
    )
    suspend fun requeueRunning()

    /** Wie [requeueRunning], aber ohne Eintraege, die im Prozess gerade noch entpackt werden. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, speedBps = 0 " +
            "WHERE status IN ('RUNNING', 'EXTRACTING') AND id NOT IN (:except)"
    )
    suspend fun requeueRunningExcept(except: List<Long>)

    /** Alle Teile eines Archiv-Sets auf "wird entpackt" setzen (auch den gerade fertigen). */
    @Query(
        "UPDATE downloads SET status = 'EXTRACTING', errorMessage = NULL, speedBps = 0 " +
            "WHERE id IN (:ids) AND status IN ('COMPLETED', 'EXTRACTING', 'RUNNING')"
    )
    suspend fun setExtractingSet(ids: List<Long>)

    /** Entpacken beendet: alle Teile des Sets zurueck auf fertig, mit Zielpfad bzw. Fehlerhinweis. */
    @Query(
        "UPDATE downloads SET status = 'COMPLETED', localPath = :path, errorMessage = :note, " +
            "attempts = 0, retryAt = 0 WHERE id IN (:ids) AND status = 'EXTRACTING'"
    )
    suspend fun completeExtractingSet(ids: List<Long>, path: String?, note: String?)

    /** Pause nur, wenn der Eintrag wirklich noch laeuft (nicht bereits fertig/entpackend). */
    @Query("UPDATE downloads SET status = 'PAUSED', speedBps = 0 WHERE id = :id AND status IN ('RUNNING', 'QUEUED')")
    suspend fun pauseIfActive(id: Long)

    /** "Nur WLAN": laufenden Download zurueck in die Warteschlange (startet bei WLAN automatisch). */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', retryAt = 0, speedBps = 0, " +
            "errorMessage = '${DownloadNotes.WAITING_WIFI}' WHERE id = :id AND status = 'RUNNING'"
    )
    suspend fun requeueIfRunning(id: Long)

    /** Abschluss nur, wenn der Eintrag nicht zwischenzeitlich pausiert/geloescht wurde. */
    @Query(
        "UPDATE downloads SET status = 'COMPLETED', localPath = :path, errorMessage = :note, " +
            "speedBps = 0, attempts = 0, retryAt = 0 " +
            "WHERE id = :id AND status IN ('RUNNING', 'EXTRACTING')"
    )
    suspend fun completeIfActive(id: Long, path: String?, note: String?): Int

    /** Fertige Teile eines Archiv-Sets nach erfolgreichem Entpacken aktualisieren. */
    @Query("UPDATE downloads SET localPath = :path, errorMessage = NULL WHERE id IN (:ids) AND status = 'COMPLETED'")
    suspend fun updateCompletedSet(ids: List<Long>, path: String?)

    /** Haengen gebliebene Linkpruefungen (Prozess-Ende) zuruecksetzen. */
    @Query("UPDATE downloads SET online = 0 WHERE online = 3")
    suspend fun resetChecking()

    /** Automatischer Wiederholversuch mit Backoff. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', attempts = :attempts, retryAt = :retryAt, " +
            "errorMessage = :error, speedBps = 0 WHERE id = :id"
    )
    suspend fun scheduleRetry(id: Long, attempts: Int, retryAt: Long, error: String?)

    /** Name und Archivschluessel gehoeren zusammen - siehe [renameFile]. */
    @Query("UPDATE downloads SET fileName = :name, archiveKey = :archiveKey WHERE id = :id")
    suspend fun setFileName(id: Long, name: String, archiveKey: String?)

    /** Ausstehende Teile des Sets (siehe [ArchiveSets.PENDING_ACTIVE]). */
    @Query(ArchiveSets.PENDING_ACTIVE)
    suspend fun pendingActiveParts(packageId: Long?, key: String, selfId: Long): Int

    /** Noch ladende Teile des Sets (siehe [ArchiveSets.PENDING_LOADING]). */
    @Query(ArchiveSets.PENDING_LOADING)
    suspend fun pendingLoadingParts(packageId: Long?, key: String, selfId: Long): Int

    /** Fertige/entpackende Teile des Sets inklusive [selfId] (siehe [ArchiveSets.SET_IDS]). */
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
    suspend fun countByArchiveKey(key: String): Int

    @Query(ArchiveSets.SAME_NAME_ELSEWHERE)
    suspend fun countSameNameElsewhere(fileName: String, packageId: Long?): Int

    /** Fertigen Eintrag von vorn laden. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attempts = 0, retryAt = 0, " +
            "downloadedBytes = 0, localPath = NULL WHERE id = :id AND status = 'COMPLETED'"
    )
    suspend fun requeueCompleted(id: Long)

    /** Nach echtem Fortschritt: Fehlversuche zaehlen von vorn. */
    @Query("UPDATE downloads SET attempts = 0 WHERE id = :id")
    suspend fun resetAttempts(id: Long)

    /** Manueller Neustart: nur fuer pausierte/gescheiterte Eintraege (laufende nie doppelt starten). */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attempts = 0, " +
            "retryAt = 0 WHERE id = :id AND status IN ('PAUSED', 'FAILED', 'OFFLINE')"
    )
    suspend fun requeue(id: Long)

    /**
     * Wartenden Eintrag sofort freigeben (Captcha im Browser geloest): nur
     * QUEUED, ein inzwischen pausierter oder laufender Eintrag bleibt unberuehrt.
     */
    @Query(
        "UPDATE downloads SET retryAt = 0, errorMessage = NULL, speedBps = 0 " +
            "WHERE id = :id AND status = 'QUEUED'"
    )
    suspend fun releaseQueued(id: Long)

    /** "Alle fortsetzen": pausierte und gescheiterte Eintraege (siehe [DownloadQueries.REQUEUE_PAUSED_AND_FAILED]). */
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

/** Dateinamen setzen und den Archivschluessel daraus ableiten - die eine Stelle dafuer. */
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
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun accountDao(): AccountDao
    abstract fun packageDao(): PackageDao

    companion object {
        /**
         * Datenbanken der Versionen 1 bis 4 (fruehe Entwicklungsstaende) werden
         * nicht mehr migriert, sondern neu aufgebaut
         * (fallbackToDestructiveMigrationFrom in [com.jdandroid.JdApp]).
         * Ab Version 5 bleiben Konten und Downloadliste bei Updates erhalten.
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
                // Doppelte URLs (aus parallelen Click'n'Load-Anfragen) bereinigen,
                // dann eindeutigen Index anlegen
                db.execSQL("DELETE FROM downloads WHERE id NOT IN (SELECT MIN(id) FROM downloads GROUP BY url)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_downloads_url ON downloads(url)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN extractProgress INTEGER NOT NULL DEFAULT -1")
            }
        }

        /**
         * Archivschluessel als Spalte: vorher wurde die Zugehoerigkeit zu einem
         * Archiv-Set bei jedem Aufruf aus allen Dateinamen berechnet. Bestehende
         * Zeilen werden aus ihrem Dateinamen nachgefuellt.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN archiveKey TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_archiveKey ON downloads(archiveKey)")
                // Erst alle Schluessel lesen, dann schreiben: nicht in die Tabelle
                // schreiben, ueber die der Cursor gerade laeuft
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
         * Spalte extractProgress entfernen: der Entpack-Stand lebt nur noch im
         * [com.jdandroid.core.ProgressBus]. SQLite kann keine Spalte loeschen,
         * daher Tabellen-Neuaufbau (Daten kopieren, alte Tabelle ersetzen);
         * dabei kommen Indizes auf status und packageId hinzu. Die CREATE-
         * Anweisungen muessen exakt dem exportierten Schema 11 entsprechen.
         *
         * Anschliessend werden gespeicherte Vermerke als Code statt als
         * deutscher Text abgelegt: die Engine sucht wartende Archiv-Teile per
         * SQL-Gleichheit, die Oberflaeche uebersetzt den Code bei der Anzeige
         * (siehe [DownloadNotes]).
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
                // Deutsche Vermerke aus aelteren Versionen in Codes wandeln
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
