package com.jdandroid.engine

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
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

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + JdApp.backgroundErrors(this, R.string.service_scope_download_service)
    )

    /** Letzte startId fuer stopSelfResult: kein Stopp, wenn gerade ein neuer Befehl eintraf. */
    @Volatile
    private var lastStartId = -1

    /** Aktueller Vordergrund-Typ (dataSync waehrend Downloads, sonst specialUse). */
    private var foregroundType = -1
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

    /**
     * startForeground wurde vom System abgelehnt (Android 14/15, Start aus dem
     * Hintergrund bzw. nach dem 6-h-Limit): dann darf nichts mehr angestossen
     * werden, sonst bleiben Eintraege als RUNNING zurueck, wenn das System den
     * Dienst gleich wieder beendet.
     */
    @Volatile
    private var foregroundRefused = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Bei Netzwechsel erneut anstossen (z.B. WLAN wieder verfuegbar). */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch { engine.onNetworkChanged() }
            }
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities
            ) {
                scope.launch { engine.onNetworkChanged() }
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
        scope.launch {
            try {
                if (foregroundRefused) return@launch
                // Erst CnL-Zustand klaeren, dann erst pumpen (sonst Race mit refresh)
                if ((application as JdApp).settings.currentClickNLoadEnabled()) {
                    cnlWanted = true
                    startClickNLoadServer()
                }
                // Nach Prozess-Neustart haengen gebliebene RUNNING/EXTRACTING-
                // Eintraege wieder einreihen - BEVOR irgendein pump() laeuft
                // Eintraege, die eine vorige Dienst-Instanz gerade noch entpackt
                // (NonCancellable), nicht neu einreihen - sonst doppelt entpackt
                (application as JdApp).db.downloadDao()
                    .requeueRunningExcept(ExtractionRegistry.activeIds())
                startupDone = true
            } finally {
                // Startsperre der Engine oeffnen (auch bei Fehler, sonst haengt pump())
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
                (application as JdApp).db.downloadDao().requeuePaused()
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
     * Android 15 begrenzt dataSync-Vordergrunddienste auf 6 Stunden je Tag.
     * Statt hart beendet zu werden, pausieren wir sauber und erklaeren es in
     * einer Benachrichtigung; "Fortsetzen" startet den Dienst neu.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.launch {
            val wasActive = engine.activeCount > 0
            engine.pauseAll()
            // Nur melden, wenn wirklich Downloads liefen - im Leerlauf (CnL an)
            // sollte der Dienst ohnehin als specialUse laufen und nicht auslaufen
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
     * Synchronisiert: der Start wird sowohl aus onCreate (Einstellung "an")
     * als auch per ACTION_START_CNL (App-Start) angestossen. Liefen beide
     * gleichzeitig, band der zweite Versuch denselben Port und meldete
     * "Address already in use", obwohl der erste Server laeuft.
     */
    @Synchronized
    private fun startClickNLoadServer() {
        cnlServer?.let { existing ->
            if (existing.isAlive) return
            // Server-Objekt vorhanden, aber tot (Socket geschlossen): neu starten
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
                // Sichtbar machen, woher die Links kamen - eine Webseite kann den
                // Server sonst unbemerkt fuettern.
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
        // Ausschliesslich Loopback: ein Server auf allen Schnittstellen waere
        // fuer jedes Geraet im WLAN erreichbar. Falls 127.0.0.1 nicht bindbar
        // ist, "localhost" (IPv6-Loopback) versuchen - nie ohne Hostnamen.
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
        // Systemtext des Sockets (nicht von uns uebersetzt), daher Textsuche
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
        // WakeLock nur halten, solange wirklich geladen wird - sonst bliebe die
        // CPU bei aktivem CnL dauerhaft wach.
        updateWakeLock(active > 0)
        // Bei aktivem Click'n'Load Server am Leben halten, damit der Port lauscht.
        // Die Stopp-Entscheidung faellt unter der Engine-Sperre (isIdle) und mit
        // stopSelfResult: ein gleichzeitig eintreffender Befehl verhindert den Stopp.
        if (startupDone && !cnlActive && engine.isIdle()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(lastStartId)
            return
        }
        ensureForegroundType(active > 0)
        val text = if (active == 0 && queued == 0 && cnlActive) {
            getString(R.string.service_status_cnl_active, ClickNLoadServer.PORT)
        } else {
            // Teile als ganze Formatstrings, mit " · " verbunden
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
     * Android 15 zaehlt das 6-Stunden-Kontingent fuer dataSync auch im
     * Leerlauf. Waehrend nichts laedt (Click'n'Load lauscht, Warten auf WLAN)
     * laeuft der Dienst daher als specialUse und wechselt erst mit dem
     * naechsten Download zurueck zu dataSync.
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
            Intent(this, MainActivity::class.java),
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

    /** Kurze, nicht dauerhafte Benachrichtigung (Click'n'Load, Zeitlimit). */
    private fun notifyEvent(id: Int, title: String, text: String, resumeAction: Boolean = false) {
        // Bei "Fortsetzen" die App oeffnen und dort den Dienst starten: nach dem
        // 6-h-Limit erlaubt Android 15 den Neustart nur aus dem Vordergrund.
        val open = PendingIntent.getActivity(
            this, if (resumeAction) 3 else 0,
            Intent(this, MainActivity::class.java).apply {
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
        // Ab Android 14 kann startForeground selbst werfen, wenn das System den
        // Start als aus dem Hintergrund kommend wertet. Das darf die App nicht
        // mitreissen: dann laeuft der Dienst eben ohne Vordergrund-Status.
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
            // Wurde der Dienst per startForegroundService angefordert und kommt
            // kein startForeground zustande, beendet das System den Prozess mit
            // ForegroundServiceDidNotStartInTimeException. Deshalb hier sauber
            // selbst beenden statt abgeschossen zu werden.
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
        /** Ganzes Paket pausieren/loeschen; [EXTRA_ID] traegt hier die Paket-ID. */
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
