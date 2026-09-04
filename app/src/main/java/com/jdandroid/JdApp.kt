package com.jdandroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.jdandroid.core.AppMessages
import com.jdandroid.data.AppDatabase
import com.jdandroid.data.LinkSink
import com.jdandroid.data.SettingsRepository
import com.jdandroid.engine.DownloadService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JdApp : Application() {

    /**
     * Hintergrundarbeit, die keinen Bildschirm braucht (z.B. Link-Pruefung).
     * Mit Exception-Handler: ein voller Speicher (SQLiteFullException) darf
     * die App nicht beenden, sondern wird als Meldung angezeigt.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + backgroundErrors("Hintergrund"))

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // WebView-Kennung im Hintergrund holen (laedt den WebView-Provider)
        Thread {
            com.jdandroid.hoster.Http.browserUserAgent =
                runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }.getOrNull()
        }.start()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "jdandroid.db")
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            // Nur bei einer aelteren App-Version als der Datenbank neu aufbauen;
            // regulaere Updates migrieren verlustfrei.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
        settings = SettingsRepository(this)
        // Die Datenschicht kennt den Download-Dienst nicht; der Start beim
        // Einreihen wird hier (Kompositionswurzel) verdrahtet.
        LinkSink.onQueued = { DownloadService.send(it, DownloadService.ACTION_PUMP) }
        createNotificationChannel()
        // Beim Prozess-Ende haengen gebliebene Linkpruefungen zuruecksetzen
        appScope.launch { db.downloadDao().resetChecking() }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "Hinweise",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Neue Links per Click'n'Load, offene Downloads nach Neustart" }
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_EVENTS = "events"

        /** Unbehandelte Fehler in Hintergrund-Coroutinen als Meldung statt Absturz. */
        fun backgroundErrors(where: String) = CoroutineExceptionHandler { _, e ->
            android.util.Log.w("JDAndroid", "$where: ${e.message}", e)
            AppMessages.error("$where: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
