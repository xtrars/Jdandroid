package com.jdandroid.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jdandroid.CrashReporter
import com.jdandroid.JdApp
import com.jdandroid.container.ContainerFiles
import com.jdandroid.engine.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val sharedText = mutableStateOf<String?>(null)
    private val dlcContent = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ab targetSdk 35 erzwingt Android 15 Edge-to-Edge. Ohne diesen Aufruf
        // werden die System-Insets nicht sauber eingerichtet und Leisten,
        // FABs oder Buttons koennen hinter den Systemleisten liegen.
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Nur beim ersten Start: nach einer Neuerstellung (Drehen, Dunkelmodus-
        // Wechsel) liefert getIntent() noch das alte VIEW/SEND-Intent - das DLC
        // wuerde sonst erneut importiert bzw. der Dialog erneut geoeffnet.
        if (savedInstanceState == null) handleIntent(intent)
        handleResumeRequest(intent)
        // Click'n'Load ueberlebt keinen Prozessneustart: ist es aktiviert, muss
        // der Dienst beim App-Start wieder hochgefahren werden, sonst lauscht
        // niemand auf dem Port obwohl der Schalter an steht. Ausserdem wartende
        // Downloads anstossen (z.B. nach Neustart des Geraets).
        lifecycleScope.launch {
            val app = application as JdApp
            if (app.settings.currentClickNLoadEnabled()) {
                DownloadService.send(this@MainActivity, DownloadService.ACTION_START_CNL)
            }
            if (withContext(Dispatchers.IO) { app.db.downloadDao().queuedCount() } > 0) {
                DownloadService.send(this@MainActivity, DownloadService.ACTION_PUMP)
            }
        }
        val settings = (application as JdApp).settings
        // Gespeicherten Modus synchron lesen: sonst zeigt der erste Frame das
        // Systemschema und springt danach um (Blitz bei jedem Kaltstart).
        val initialMode = runBlocking { settings.themeMode.first() }
        val initialDynamic = runBlocking { settings.dynamicColors.first() }
        setContent {
            val modeKey by settings.themeMode.collectAsState(initial = initialMode)
            val dynamic by settings.dynamicColors.collectAsState(initial = initialDynamic)
            val mode = ThemeMode.fromKey(modeKey)
            val dark = isDarkFor(mode)
            // Systemleisten zur gewaehlten Helligkeit passend einfaerben - auch
            // wenn der Modus manuell vom System abweicht.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                // Fensterhintergrund mitziehen, damit beim Drehen kein heller
                // Streifen unter der Oberflaeche aufblitzt
                window.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(if (dark) 0xFF0F1417.toInt() else 0xFFF6F9FB.toInt())
                )
            }
            JdTheme(mode = mode, dynamicColors = dynamic) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        sharedText = sharedText.value,
                        onSharedTextConsumed = { sharedText.value = null },
                        dlcContent = dlcContent.value,
                        onDlcConsumed = { dlcContent.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        handleResumeRequest(intent)
    }

    /**
     * "Fortsetzen" aus der Zeitlimit-Benachrichtigung: der Dienst darf auf
     * Android 15 nach dem 6-h-Limit nur aus dem Vordergrund neu starten -
     * also ueber die Activity, nicht per PendingIntent auf den Dienst.
     */
    private fun handleResumeRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(DownloadService.EXTRA_RESUME_ALL, false) == true) {
            intent.removeExtra(DownloadService.EXTRA_RESUME_ALL)
            DownloadService.send(this, DownloadService.ACTION_RESUME_ALL)
        }
    }

    /** Verteilt eingehende Intents auf geteilten Text bzw. DLC-Dateien. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) { sharedText.value = text; return }
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) {
            // Groessenbegrenzt und im Hintergrund lesen: der Filter nimmt jede
            // octet-stream-Datei an, und Cloud-Anbieter laden die Datei beim
            // Oeffnen erst herunter (sonst ANR auf dem Hauptthread).
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching { ContainerFiles.readText(contentResolver, uri) }
                val content = result.getOrNull()
                if (content != null && ContainerFiles.looksLikeDlc(content)) {
                    withContext(Dispatchers.Main) { dlcContent.value = content }
                } else {
                    AppMessages.error(
                        result.exceptionOrNull()?.message
                            ?: "Die geöffnete Datei ist kein DLC-Container."
                    )
                }
            }
        }
    }
}

private enum class Tab(val label: String) {
    Downloads("Downloads"), Collector("Linksammler"), Accounts("Konten"), Settings("Einstellungen")
}

@Composable
fun MainScreen(
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
    dlcContent: String?,
    onDlcConsumed: () -> Unit
) {
    // Tab ueber Drehen/Neuerstellung behalten (Enums sind Serializable und
    // damit direkt im Bundle sicherbar)
    var tab by rememberSaveable { mutableStateOf(Tab.Downloads) }
    val context = LocalContext.current
    val autoStart by (context.applicationContext as JdApp).settings.autoStartLinks.collectAsState(initial = false)
    // Ein DLC gehoert in den Linksammler (bei Sofortstart in die Downloads):
    // dorthin wechseln, die Meldung erscheint dort
    LaunchedEffect(dlcContent) { if (dlcContent != null) tab = if (autoStart) Tab.Downloads else Tab.Collector }
    // Geteilter Text oeffnet den Dialog im Downloads-Tab - also dorthin wechseln,
    // sonst passiert bei offenem Konten-/Einstellungs-Tab sichtbar nichts
    LaunchedEffect(sharedText) { if (sharedText != null) tab = Tab.Downloads }

    // Zentrale Meldungen (DLC-Import, Kontofehler, ...): eine Fortschrittsmeldung
    // bleibt stehen, bis die naechste Meldung sie abloest.
    val messageHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // Den laufenden Anzeige-Job abbrechen statt nur currentSnackbarData zu
        // schliessen: kommt das Ergebnis direkt nach der Fortschrittsmeldung,
        // ist die noch gar nicht sichtbar und bliebe sonst endlos stehen.
        var showing: Job? = null
        AppMessages.events.collect { message ->
            AppMessages.markShown()
            showing?.cancel()
            showing = launch { messageHost.showSnackbar(message) }
        }
    }
    var crashReport by remember { mutableStateOf(CrashReporter.lastCrash(context)) }
    crashReport?.let { report ->
        CrashDialog(report = report, onDismiss = { crashReport = null })
    }
    val downloadVm: DownloadViewModel = viewModel()
    val accountVm: AccountViewModel = viewModel()

    // Browser-Login ersetzt vorruebergehend den ganzen Bildschirm, damit die
    // System-Insets greifen und die Schaltflaechen sichtbar bleiben.
    val webLoginHoster by accountVm.webLogin.collectAsState()
    val webLoginUrl = webLoginHoster?.webLoginUrl
    if (webLoginUrl != null) {
        WebLoginScreen(
            loginUrl = webLoginUrl,
            onCancel = { accountVm.cancelWebLogin() },
            onAccept = { accountVm.completeWebLogin(it) }
        )
        return
    }

    Scaffold(
        // Die Insets behandeln die inneren Bildschirme (eigene TopAppBar) und
        // die NavigationBar selbst - sonst kaeme der Abstand doppelt.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { JdSnackbarHost(messageHost) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Downloads -> Icons.Default.Download
                                    Tab.Collector -> Icons.Default.Link
                                    Tab.Accounts -> Icons.Default.Person
                                    Tab.Settings -> Icons.Default.Settings
                                },
                                contentDescription = t.label
                            )
                        },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Downloads -> DownloadsScreen(
                downloadVm, sharedText, onSharedTextConsumed,
                onLinksAdded = { toCollector -> tab = if (toCollector) Tab.Collector else Tab.Downloads },
                modifier = modifier
            )
            Tab.Collector -> LinkGrabberScreen(downloadVm, dlcContent, onDlcConsumed, modifier)
            Tab.Accounts -> AccountsScreen(accountVm, modifier)
            Tab.Settings -> SettingsScreen(modifier)
        }
    }
}
