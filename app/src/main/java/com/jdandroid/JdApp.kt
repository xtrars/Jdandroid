package com.jdandroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.jdandroid.data.AppDatabase
import com.jdandroid.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class JdApp : Application() {

    /** Hintergrundarbeit, die keinen Bildschirm braucht (z.B. Link-Pruefung). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        db = Room.databaseBuilder(this, AppDatabase::class.java, "jdandroid.db")
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            // Nur bei einer aelteren App-Version als der Datenbank neu aufbauen;
            // regulaere Updates migrieren verlustfrei.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
        settings = SettingsRepository(this)
        Diagnostics.sink = { key, title, text -> Diagnostics.save(this, key, title, text) }
        createNotificationChannel()
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
    }
}
