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
     * Scope for background work without a screen (e.g. link checks). The
     * exception handler turns failures such as SQLiteFullException into a
     * message instead of a crash.
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
        Texts.install(ResourceTexts(this))
        // Off the main thread: this loads the WebView provider.
        Thread {
            com.jdandroid.hoster.Http.browserUserAgent =
                runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }.getOrNull()
        }.start()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "jdandroid.db")
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            // Early schema versions are rebuilt; later ones migrate losslessly.
            .fallbackToDestructiveMigrationFrom(true, *AppDatabase.DESTRUCTIVE_FROM)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
        settings = SettingsRepository(this)
        // The data layer does not know the download service; wire it up here.
        LinkSink.onQueued = { DownloadService.send(it, DownloadService.ACTION_PUMP) }
        createNotificationChannel()
        // Link checks interrupted by a process death would otherwise stay "checking".
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
         * Reports uncaught coroutine errors as a message instead of a crash.
         * [whereRes] is resolved lazily: the handler is created in field
         * initializers, before the context is attached.
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
