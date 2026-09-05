package com.jdandroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import com.jdandroid.core.AppMessages
import com.jdandroid.core.Texts
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
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + backgroundErrors(this, R.string.service_scope_background)
    )

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Texte der Engine/Hoster-Schicht in der Geraetesprache aufloesen
        Texts.install(ResourceTexts(this))
        // WebView-Kennung im Hintergrund holen (laedt den WebView-Provider)
        Thread {
            com.jdandroid.hoster.Http.browserUserAgent =
                runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }.getOrNull()
        }.start()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "jdandroid.db")
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            // Fruehe Entwicklungsstaende (Version 1-4) werden neu aufgebaut;
            // ab Version 5 migrieren Updates verlustfrei.
            .fallbackToDestructiveMigrationFrom(true, *AppDatabase.DESTRUCTIVE_FROM)
            // Nur bei einer aelteren App-Version als der Datenbank neu aufbauen
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
                getString(R.string.service_channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                getString(R.string.service_channel_events),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.service_channel_events_description) }
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_EVENTS = "events"

        /**
         * Unbehandelte Fehler in Hintergrund-Coroutinen als Meldung statt
         * Absturz. [whereRes] ist der Bereichsname; er wird erst im Fehlerfall
         * aufgeloest, weil der Handler in Feld-Initialisierern entsteht, wenn
         * der Context noch nicht angebunden ist.
         */
        fun backgroundErrors(context: Context, whereRes: Int) = CoroutineExceptionHandler { _, e ->
            val where = context.getString(whereRes)
            android.util.Log.w("JDAndroid", "$where: ${e.message}", e)
            AppMessages.error(
                context.getString(R.string.service_background_error, where, e.message ?: e.javaClass.simpleName)
            )
        }
    }
}
