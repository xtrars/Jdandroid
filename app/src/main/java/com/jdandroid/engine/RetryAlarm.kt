package com.jdandroid.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * [WakeUpAlarm] on the system alarm clock. Exact where the user allows exact
 * alarms, otherwise the system may shift it by a few minutes; both fire in
 * Doze. One pending alarm at a time: a new time replaces the old one.
 */
internal class RetryAlarm(context: Context) : WakeUpAlarm {
    private val context = context.applicationContext

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, RetryAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun schedule(wallMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wallMillis, pendingIntent())
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wallMillis, pendingIntent())
            }
        }
    }

    override fun cancel() {
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent()) }
    }
}

/**
 * Fires when a retry is due. Keeps the CPU awake for a moment, since the
 * system only guarantees it during onReceive, and lets the service start the
 * due entries; while one transfers, the service holds its own wake lock.
 */
class RetryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        wakeLock(context).acquire(WAKE_MS)
        DownloadService.send(context, DownloadService.ACTION_PUMP)
    }

    private companion object {
        const val WAKE_MS = 60_000L

        @Volatile
        private var lock: PowerManager.WakeLock? = null

        fun wakeLock(context: Context): PowerManager.WakeLock = lock ?: synchronized(this) {
            lock ?: context.applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jdandroid:retry")
                .apply { setReferenceCounted(false) }
                .also { lock = it }
        }
    }
}
