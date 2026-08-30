package com.jdandroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.jdandroid.data.AppDatabase
import com.jdandroid.data.SettingsRepository

class JdApp : Application() {

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
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
