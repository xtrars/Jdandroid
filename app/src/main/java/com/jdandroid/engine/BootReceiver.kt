package com.jdandroid.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Nach Neustart oder App-Update: haengen gebliebene "laufende" Downloads
 * wieder einreihen und - falls etwas offen ist - per Benachrichtigung daran
 * erinnern. Der Download-Dienst selbst darf ab Android 15 nicht mehr aus
 * BOOT_COMPLETED heraus als dataSync-Vordergrunddienst starten; ein Tipp auf
 * die Benachrichtigung oeffnet die App, die ihn dann regulaer startet.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        val app = context.applicationContext as JdApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = app.db.downloadDao()
                dao.requeueRunning()
                val open = dao.queuedCount()
                if (open > 0) notifyPending(context, open)
            } catch (_: Exception) {
                // Nichts zu retten - die App stellt den Zustand beim Start her
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyPending(context: Context, count: Int) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, JdApp.CHANNEL_EVENTS)
            .setSmallIcon(com.jdandroid.R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                context.resources.getQuantityString(R.plurals.service_boot_pending_downloads, count, count)
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 3
    }
}
