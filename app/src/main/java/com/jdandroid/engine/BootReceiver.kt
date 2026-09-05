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
 * After a reboot or app update, requeues downloads left in RUNNING and posts a
 * reminder notification if anything is open. Since Android 15 a dataSync
 * foreground service may not be started from BOOT_COMPLETED, so the
 * notification opens the app, which starts the service normally.
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
                // The app repairs the state on its next start.
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
