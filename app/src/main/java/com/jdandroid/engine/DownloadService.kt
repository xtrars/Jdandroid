package com.jdandroid.engine

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.container.ClickNLoadServer
import com.jdandroid.container.CnlRequest
import com.jdandroid.container.CnlStatus
import com.jdandroid.core.AppLog
import com.jdandroid.core.AppMessages
import com.jdandroid.core.formatBytes
import com.jdandroid.data.LinkSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the [DownloadEngine], the Click'n'Load
 * server and the progress notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + JdApp.backgroundErrors(this, R.string.service_scope_download_service)
    )

    /** For stopSelfResult: no stop if a newer command has arrived meanwhile. */
    @Volatile
    private var lastStartId = -1

    /** Current foreground type: dataSync while downloading, specialUse otherwise. */
    private var foregroundType = -1
    private lateinit var engine: DownloadEngine
    private var wakeLock: PowerManager.WakeLock? = null
    private var cnlServer: ClickNLoadServer? = null

    /**
     * Desired Click'n'Load state, set synchronously while the server starts
     * asynchronously; otherwise refresh() could stop the service before
     * cnlServer is assigned.
     */
    @Volatile
    private var cnlWanted = false

    /** The service must not stop itself before startup has finished. */
    @Volatile
    private var startupDone = false

    /**
     * startForeground was refused (Android 14/15: background start or the 6 h
     * limit). Nothing may be started then, or entries stay RUNNING when the
     * system kills the service right away.
     */
    @Volatile
    private var foregroundRefused = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val filter = NetworkChangeFilter()
        val callback = object : ConnectivityManager.NetworkCallback() {
            // onAvailable is always followed by onCapabilitiesChanged (API 26+),
            // which then reports the change once with the metered state known
            override fun onAvailable(network: android.net.Network) {
                filter.onAvailable()
            }
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities
            ) {
                val changed = filter.onCapabilities(
                    network,
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                )
                if (changed) scope.launch { engine.onNetworkChanged() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jdandroid:downloads")
        engine = DownloadEngine(this, scope) { scope.launch { refresh() } }
        startForegroundCompat(buildNotification(getString(R.string.service_preparing)))
        // Already stopping: no startup, no network callback
        if (foregroundRefused) return
        scope.launch {
            try {
                // CnL state first, pump later (race with refresh otherwise)
                if ((application as JdApp).settings.currentClickNLoadEnabled()) {
                    cnlWanted = true
                    startClickNLoadServer()
                }
                // Requeue RUNNING/EXTRACTING entries left by a dead process before
                // any pump() runs; entries a previous service instance is still
                // extracting (NonCancellable) stay as they are.
                (application as JdApp).db.downloadDao()
                    .requeueRunningExcept(ExtractionRegistry.activeIds())
                startupDone = true
            } finally {
                // Open the engine's start gate even on failure, or pump() hangs
                engine.markReady()
            }
            engine.pump()
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (foregroundRefused) return START_NOT_STICKY
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_PUMP -> scope.launch { engine.pump() }
            ACTION_PAUSE -> scope.launch { engine.pause(id) }
            ACTION_DELETE -> scope.launch { engine.cancelAndDelete(id) }
            ACTION_PAUSE_PACKAGE -> scope.launch { engine.pausePackage(id) }
            ACTION_DELETE_PACKAGE -> scope.launch { engine.deletePackage(id) }
            ACTION_EXTRACT -> scope.launch {
                engine.extractNow(id)?.let { AppMessages.error(it) }
            }
            ACTION_PAUSE_ALL -> scope.launch { engine.pauseAll(); refresh() }
            ACTION_RESUME_ALL -> scope.launch {
                (application as JdApp).db.downloadDao().requeuePausedAndFailed()
                engine.pump()
            }
            ACTION_START_CNL -> {
                cnlWanted = true
                scope.launch { startClickNLoadServer(); refresh() }
            }
            ACTION_STOP_CNL -> {
                cnlWanted = false
                cnlServer?.stop()
                cnlServer = null
                CnlStatus.stopped()
                scope.launch { refresh() }
            }
            else -> scope.launch { engine.pump() }
        }
        return START_STICKY
    }

    /**
     * Android 15 limits dataSync foreground services to 6 hours per day.
     * Pause cleanly and explain it in a notification whose "Resume" restarts
     * the service.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.launch {
            val wasActive = engine.activeCount > 0
            engine.pauseAll()
            // Idle with CnL on, the service should run as specialUse and not time out
            if (wasActive) {
                notifyEvent(
                    NOTIFICATION_TIMEOUT,
                    getString(R.string.service_timeout_paused_title),
                    getString(R.string.service_timeout_paused_text),
                    resumeAction = true
                )
            } else if (cnlWanted) {
                notifyEvent(
                    NOTIFICATION_TIMEOUT,
                    getString(R.string.service_timeout_cnl_title),
                    getString(R.string.service_timeout_cnl_text)
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Synchronized: onCreate (setting enabled) and ACTION_START_CNL (app start)
     * can both trigger the start; a concurrent second attempt would fail with
     * "Address already in use" although the first server runs.
     */
    @Synchronized
    private fun startClickNLoadServer() {
        cnlServer?.let { existing ->
            if (existing.isAlive) return
            runCatching { existing.stop() }
            cnlServer = null
        }
        val onRequest: (CnlRequest) -> Unit = { request ->
            scope.launch {
                val added = LinkSink.addUrls(
                    applicationContext, request.urls,
                    packageName = request.packageName,
                    source = request.source,
                    passwords = request.passwords
                )
                // Show where the links came from: a web page could feed the server unnoticed
                val origin = request.source?.let { LinkSink.displaySource(it) }
                    ?: getString(R.string.service_cnl_origin_website)
                notifyEvent(
                    NOTIFICATION_CNL,
                    getString(R.string.service_cnl_title),
                    if (added > 0) {
                        resources.getQuantityString(R.plurals.service_cnl_links_added, added, added, origin)
                    } else {
                        getString(R.string.service_cnl_nothing_added, origin)
                    }
                )
            }
        }
        // Loopback only: a server on all interfaces would be reachable from the
        // whole LAN. If 127.0.0.1 cannot be bound, try "localhost" (IPv6 loopback).
        var lastError: Exception? = null
        for (host in listOf(ClickNLoadServer.LOOPBACK, "localhost")) {
            try {
                val server = ClickNLoadServer(host, onRequest = onRequest)
                server.start()
                cnlServer = server
                CnlStatus.started(host)
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        val raw = lastError?.message ?: lastError?.javaClass?.simpleName
            ?: getString(R.string.service_error_unknown)
        // Socket error text comes from the system, hence the text search
        val reason = if (raw.contains("in use", true) || raw.contains("EADDRINUSE", true)) {
            getString(R.string.service_cnl_port_in_use, ClickNLoadServer.PORT)
        } else raw
        CnlStatus.failed(reason)
        AppLog.w("DownloadService", "CNL-Start fehlgeschlagen: $reason")
    }

    private suspend fun refresh() {
        val dao = (application as JdApp).db.downloadDao()
        val queued = dao.queuedCount()
        val active = engine.activeCount
        val cnlActive = cnlWanted
        // Hold the wake lock only while transferring, or CnL keeps the CPU awake for good
        updateWakeLock(active > 0)
        // With CnL on the service stays alive for the port. The stop decision is
        // made under the engine lock (isIdle) and with stopSelfResult, so a
        // concurrent command prevents it; the foreground status is dropped only
        // once the stop is certain, otherwise a plain background service remained.
        if (startupDone && !cnlActive && engine.isIdle() && stopSelfResult(lastStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        ensureForegroundType(active > 0)
        val text = if (active == 0 && queued == 0 && cnlActive) {
            getString(R.string.service_status_cnl_active, ClickNLoadServer.PORT)
        } else {
            val parts = mutableListOf(
                if (queued > 0) resources.getQuantityString(R.plurals.service_status_active_queued, active, active, queued)
                else resources.getQuantityString(R.plurals.service_status_active, active, active)
            )
            val speed = engine.totalSpeedBps
            if (speed > 0) parts += getString(R.string.service_status_speed, formatBytes(speed))
            if (cnlActive) parts += getString(R.string.service_status_cnl_on)
            parts.joinToString(" · ")
        }
        val done = engine.openDownloadedBytes()
        val total = dao.openTotalBytes()
        val progress = if (active > 0 && total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
        val paused = dao.pausedCount()
        val manager = getSystemService(NotificationManager::class.java)
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification(text, progress, active > 0, paused > 0))
        }
    }

    /**
     * Android 15 counts the 6 h dataSync quota while idle too, so the service
     * runs as specialUse while nothing transfers (CnL listening, waiting for
     * Wi-Fi) and switches back to dataSync with the next download.
     */
    private fun ensureForegroundType(downloading: Boolean) {
        if (Build.VERSION.SDK_INT < 34) return
        val wanted = if (downloading) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (wanted == foregroundType) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.service_preparing)), wanted)
            foregroundType = wanted
        } catch (e: Exception) {
            android.util.Log.w("DownloadService", "Typwechsel abgelehnt: ${e.message}")
        }
    }

    private fun updateWakeLock(shouldHold: Boolean) {
        val lock = wakeLock ?: return
        if (shouldHold && !lock.isHeld) {
            lock.acquire(6 * 60 * 60 * 1000L)
        } else if (!shouldHold && lock.isHeld) {
            lock.release()
        }
    }

    private fun servicePendingIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(
        text: String,
        progress: Int = -1,
        showPause: Boolean = false,
        showResume: Boolean = false
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            appLaunchIntent(),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, JdApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(com.jdandroid.R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
        if (progress >= 0) builder.setProgress(100, progress, false)
        if (showPause) {
            builder.addAction(
                0, getString(R.string.service_action_pause_all), servicePendingIntent(ACTION_PAUSE_ALL, 1)
            )
        }
        if (showResume) {
            builder.addAction(
                0, getString(R.string.common_resume), servicePendingIntent(ACTION_RESUME_ALL, 2)
            )
        }
        return builder.build()
    }

    /** Short, dismissable notification (Click'n'Load, timeout). */
    private fun notifyEvent(id: Int, title: String, text: String, resumeAction: Boolean = false) {
        // "Resume" opens the app, which starts the service: after the 6 h limit
        // Android 15 allows a restart only from the foreground.
        val open = PendingIntent.getActivity(
            this, if (resumeAction) 3 else 0,
            appLaunchIntent().apply {
                if (resumeAction) putExtra(EXTRA_RESUME_ALL, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, JdApp.CHANNEL_EVENTS)
            .setSmallIcon(com.jdandroid.R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (resumeAction) {
            builder.addAction(0, getString(R.string.common_resume), open)
        }
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(id, builder.build())
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        // Since Android 14 startForeground itself may throw when the system
        // considers the start to come from the background.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Requested via startForegroundService without a successful
            // startForeground, the process would be killed with
            // ForegroundServiceDidNotStartInTimeException; stop cleanly instead.
            android.util.Log.w("DownloadService", "startForeground abgelehnt: ${e.message}")
            foregroundRefused = true
            runCatching { stopSelf() }
        }
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        cnlServer?.stop()
        CnlStatus.stopped()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CNL = 2
        private const val NOTIFICATION_TIMEOUT = 4
        const val ACTION_PUMP = "com.jdandroid.action.PUMP"
        const val ACTION_PAUSE = "com.jdandroid.action.PAUSE"
        const val ACTION_DELETE = "com.jdandroid.action.DELETE"
        /** Package-wide actions: [EXTRA_ID] carries the package id. */
        const val ACTION_PAUSE_PACKAGE = "com.jdandroid.action.PAUSE_PACKAGE"
        const val ACTION_DELETE_PACKAGE = "com.jdandroid.action.DELETE_PACKAGE"
        const val ACTION_EXTRACT = "com.jdandroid.action.EXTRACT"
        const val ACTION_PAUSE_ALL = "com.jdandroid.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.jdandroid.action.RESUME_ALL"
        const val ACTION_START_CNL = "com.jdandroid.action.START_CNL"
        const val ACTION_STOP_CNL = "com.jdandroid.action.STOP_CNL"
        const val EXTRA_ID = "id"
        const val EXTRA_RESUME_ALL = "resume_all"

        fun send(context: Context, action: String, id: Long = -1) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(action)
                .putExtra(EXTRA_ID, id)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Forbidden since Android 12 while the app counts as background
                // (share sheet, Click'n'Load with the app closed): fall back to a
                // plain start; the queue continues when the app is opened next.
                android.util.Log.w("DownloadService", "Start abgelehnt: ${e.message}")
                runCatching { context.startService(intent) }
            }
        }
    }
}
