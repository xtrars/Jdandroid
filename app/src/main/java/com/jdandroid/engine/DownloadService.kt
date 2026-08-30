package com.jdandroid.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.data.DownloadStatus
import com.jdandroid.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-Service, der die DownloadEngine am Leben haelt und den
 * Gesamtfortschritt als Benachrichtigung anzeigt.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: DownloadEngine

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine(this, scope) { scope.launch { refresh() } }
        startForegroundCompat(buildNotification("Downloads werden vorbereitet …"))
        // Nach Prozess-Neustart haengen gebliebene RUNNING-Eintraege wieder einreihen
        scope.launch {
            (application as JdApp).db.downloadDao().requeueRunning()
            engine.pump()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_PUMP -> scope.launch { engine.pump() }
            ACTION_PAUSE -> scope.launch { engine.pause(id) }
            ACTION_DELETE -> scope.launch { engine.cancelAndDelete(id) }
            ACTION_PAUSE_ALL -> scope.launch { engine.pauseAll(); refresh() }
            else -> scope.launch { engine.pump() }
        }
        return START_STICKY
    }

    private suspend fun refresh() {
        val dao = (application as JdApp).db.downloadDao()
        val queued = dao.queuedCount()
        val active = engine.activeCount
        if (active == 0 && queued == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val text = buildString {
            append("$active aktiv")
            if (queued > 0) append(", $queued wartend")
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, JdApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_PUMP = "com.jdandroid.action.PUMP"
        const val ACTION_PAUSE = "com.jdandroid.action.PAUSE"
        const val ACTION_DELETE = "com.jdandroid.action.DELETE"
        const val ACTION_PAUSE_ALL = "com.jdandroid.action.PAUSE_ALL"
        const val EXTRA_ID = "id"

        fun send(context: Context, action: String, id: Long = -1) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(action)
                .putExtra(EXTRA_ID, id)
            context.startForegroundService(intent)
        }
    }
}
