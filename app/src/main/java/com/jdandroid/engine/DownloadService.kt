package com.jdandroid.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.container.ClickNLoadServer
import com.jdandroid.container.CnlStatus
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.LinkSink
import com.jdandroid.ui.MainActivity
import fi.iki.elonen.NanoHTTPD
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var cnlServer: ClickNLoadServer? = null

    /**
     * Gewuenschter Click'n'Load-Zustand. Wird synchron gesetzt, waehrend der
     * Server selbst asynchron startet: sonst koennte refresh() den Service
     * beenden, bevor cnlServer gesetzt ist, und CnL liefe nie.
     */
    @Volatile
    private var cnlWanted = false

    /** Vor Abschluss des Starts darf sich der Service nicht selbst beenden. */
    @Volatile
    private var startupDone = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Bei Netzwechsel erneut anstossen (z.B. WLAN wieder verfuegbar). */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch { engine.pump() }
            }
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities
            ) {
                scope.launch { engine.pump() }
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
        startForegroundCompat(buildNotification("Downloads werden vorbereitet …"))
        scope.launch {
            // Erst CnL-Zustand klaeren, dann erst pumpen (sonst Race mit refresh)
            if ((application as JdApp).settings.currentClickNLoadEnabled()) {
                cnlWanted = true
                startClickNLoadServer()
            }
            startupDone = true
            // Nach Prozess-Neustart haengen gebliebene RUNNING-Eintraege wieder einreihen
            (application as JdApp).db.downloadDao().requeueRunning()
            engine.pump()
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_PUMP -> scope.launch { engine.pump() }
            ACTION_PAUSE -> scope.launch { engine.pause(id) }
            ACTION_DELETE -> scope.launch { engine.cancelAndDelete(id) }
            ACTION_PAUSE_ALL -> scope.launch { engine.pauseAll(); refresh() }
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

    private fun startClickNLoadServer() {
        if (cnlServer != null) return
        val onLinks: (List<String>) -> Unit = { links ->
            scope.launch { LinkSink.addUrls(applicationContext, links) }
        }
        // Zuerst nur loopback (sicher). Schlaegt das fehl - auf manchen Geraeten
        // laesst sich 127.0.0.1 nicht binden - auf alle Schnittstellen ausweichen.
        var lastError: Exception? = null
        for (host in listOf<String?>(ClickNLoadServer.LOOPBACK, null)) {
            try {
                val server = ClickNLoadServer(host, onLinks)
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                cnlServer = server
                CnlStatus.started(host ?: "alle Schnittstellen")
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        val reason = lastError?.message ?: lastError?.javaClass?.simpleName ?: "unbekannt"
        CnlStatus.failed(reason)
        android.util.Log.w("DownloadService", "CNL-Start fehlgeschlagen: $reason")
    }

    private suspend fun refresh() {
        val dao = (application as JdApp).db.downloadDao()
        val queued = dao.queuedCount()
        val active = engine.activeCount
        val cnlActive = cnlWanted
        // WakeLock nur halten, solange wirklich geladen wird - sonst bliebe die
        // CPU bei aktivem CnL dauerhaft wach.
        updateWakeLock(active > 0)
        // Bei aktivem Click'n'Load Server am Leben halten, damit der Port lauscht
        if (startupDone && active == 0 && queued == 0 && !cnlActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val text = buildString {
            if (active == 0 && queued == 0 && cnlActive) {
                append("Click'n'Load aktiv (Port ${ClickNLoadServer.PORT})")
            } else {
                append("$active aktiv")
                if (queued > 0) append(", $queued wartend")
                if (cnlActive) append(" · CnL an")
            }
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun updateWakeLock(shouldHold: Boolean) {
        val lock = wakeLock ?: return
        if (shouldHold && !lock.isHeld) {
            lock.acquire(6 * 60 * 60 * 1000L)
        } else if (!shouldHold && lock.isHeld) {
            lock.release()
        }
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
        // Ab Android 14 kann startForeground selbst werfen, wenn das System den
        // Start als aus dem Hintergrund kommend wertet. Das darf die App nicht
        // mitreissen: dann laeuft der Dienst eben ohne Vordergrund-Status.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Wurde der Dienst per startForegroundService angefordert und kommt
            // kein startForeground zustande, beendet das System den Prozess mit
            // ForegroundServiceDidNotStartInTimeException. Deshalb hier sauber
            // selbst beenden statt abgeschossen zu werden.
            android.util.Log.w("DownloadService", "startForeground abgelehnt: ${e.message}")
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
        const val ACTION_PUMP = "com.jdandroid.action.PUMP"
        const val ACTION_PAUSE = "com.jdandroid.action.PAUSE"
        const val ACTION_DELETE = "com.jdandroid.action.DELETE"
        const val ACTION_PAUSE_ALL = "com.jdandroid.action.PAUSE_ALL"
        const val ACTION_START_CNL = "com.jdandroid.action.START_CNL"
        const val ACTION_STOP_CNL = "com.jdandroid.action.STOP_CNL"
        const val EXTRA_ID = "id"

        fun send(context: Context, action: String, id: Long = -1) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(action)
                .putExtra(EXTRA_ID, id)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Ab Android 12 verboten, wenn die App als "im Hintergrund" gilt
                // (z.B. Link per Teilen oder Click'n'Load bei geschlossener App).
                // Das darf die App nicht abstuerzen lassen - regulaerer Start als
                // Rueckfallebene, sonst laeuft die Warteschlange beim naechsten
                // Oeffnen der App weiter.
                android.util.Log.w("DownloadService", "Start abgelehnt: ${e.message}")
                runCatching { context.startService(intent) }
            }
        }
    }
}
