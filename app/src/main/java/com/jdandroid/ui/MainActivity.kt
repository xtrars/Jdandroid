package com.jdandroid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jdandroid.CrashReporter
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.container.ContainerFiles
import com.jdandroid.core.AppMessages
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
        // Nur beim ersten Start und nur, wenn die Berechtigung fehlt: sonst
        // erscheint der Systemdialog bei jedem Drehen erneut.
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
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
            // Auch haengen gebliebene RUNNING/EXTRACTING zaehlen: der Dienst setzt
            // sie beim Start zurueck und laedt weiter
            if (withContext(Dispatchers.IO) { app.db.downloadDao().openCount() } > 0) {
                DownloadService.send(this@MainActivity, DownloadService.ACTION_PUMP)
            }
        }
        val settings = (application as JdApp).settings
        // Gespeicherten Modus synchron lesen: sonst zeigt der erste Frame das
        // Systemschema und springt danach um (Blitz bei jedem Kaltstart).
        // Ein einziger blockierender Aufruf; schlaegt das Lesen fehl, gilt
        // das Systemschema statt eines Absturzes beim Start.
        val initialMode = try {
            runBlocking { settings.themeMode.first() }
        } catch (e: Exception) {
            "system"
        }
        setContent {
            val modeKey by settings.themeMode.collectAsStateWithLifecycle(initialValue = initialMode)
            val mode = ThemeMode.fromKey(modeKey)
            val dark = isDarkFor(mode)
            // Systemleisten zur gewaehlten Helligkeit passend einfaerben - auch
            // wenn der Modus manuell vom System abweicht.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            JdTheme(mode = mode) {
                // Fensterhintergrund in der Schemafarbe, damit beim Drehen kein
                // andersfarbiger Streifen unter der Oberflaeche aufblitzt
                val background = MaterialTheme.colorScheme.background
                LaunchedEffect(background) {
                    window.setBackgroundDrawable(background.toArgb().toDrawable())
                }
                Surface(color = background) {
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
                            ?: getString(R.string.accounts_not_dlc_opened)
                    )
                }
            }
        }
    }
}

/** Tabs der unteren Leiste; [labelRes] ist die uebersetzte Beschriftung. */
private enum class Tab(val labelRes: Int) {
    Downloads(R.string.accounts_tab_downloads),
    Collector(R.string.accounts_tab_collector),
    Accounts(R.string.accounts_title),
    Settings(R.string.accounts_tab_settings)
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
    val resources = LocalResources.current
    val settings = (context.applicationContext as JdApp).settings
    val downloadVm: DownloadViewModel = viewModel()
    val accountVm: AccountViewModel = viewModel()

    // Die Dialoge "Links hinzufuegen" und "Konto hinzufuegen" werden von hier
    // aus geoeffnet (Plus-Knopf der aeusseren Scaffold, geteilter Text) und
    // ueberleben Drehen und Tabwechsel.
    var showAddLinks by rememberSaveable { mutableStateOf(false) }
    var addLinksPrefill by rememberSaveable { mutableStateOf("") }
    var showAddAccount by rememberSaveable { mutableStateOf(false) }

    // Ein DLC wird unabhaengig vom offenen Tab importiert (der Linksammler
    // ist bei Sofortstart gar nicht aufgebaut); danach dorthin wechseln, wo
    // die Links landen.
    LaunchedEffect(dlcContent) {
        if (dlcContent != null) {
            downloadVm.importDlc(dlcContent)
            onDlcConsumed()
            tab = if (settings.currentAutoStartLinks()) Tab.Downloads else Tab.Collector
        }
    }
    // Geteilter Text oeffnet den Dialog im Linksammler - also dorthin wechseln,
    // sonst passiert bei offenem Konten-/Einstellungs-Tab sichtbar nichts
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            addLinksPrefill = sharedText
            showAddLinks = true
            onSharedTextConsumed()
            tab = Tab.Collector
        }
    }
    // Zurueck fuehrt erst zu den Downloads, dann aus der App. Die Bildschirme
    // registrieren eigene Handler (Suche schliessen, Browser-Login) spaeter
    // und haben damit Vorrang.
    BackHandler(enabled = tab != Tab.Downloads) { tab = Tab.Downloads }

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
            showing = launch { messageHost.showSnackbar(JdMessage(message.text, message.kind)) }
        }
    }
    var crashReport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashReport = withContext(Dispatchers.IO) { CrashReporter.lastCrash(context) }
    }
    crashReport?.let { report ->
        CrashDialog(report = report, onDismiss = { crashReport = null })
    }

    // Browser-Login ersetzt vorruebergehend den ganzen Bildschirm, damit die
    // System-Insets greifen und die Schaltflaechen sichtbar bleiben.
    val webLoginHoster by accountVm.webLogin.collectAsStateWithLifecycle()
    val webLoginUrl = webLoginHoster?.webLoginUrl
    if (webLoginUrl != null) {
        val webLoginStatus by accountVm.webLoginStatus.collectAsStateWithLifecycle()
        val webLoginCookies by accountVm.webLoginCookies.collectAsStateWithLifecycle()
        WebLoginScreen(
            loginUrl = webLoginUrl,
            status = webLoginStatus,
            detectedCookies = webLoginCookies,
            onStatusChange = { accountVm.setWebLoginStatus(it) },
            onCookiesDetected = { accountVm.setWebLoginCookies(it) },
            onCancel = { accountVm.cancelWebLogin() },
            onAccept = { accountVm.completeWebLogin(it) }
        )
        return
    }

    // Captcha-Ansicht des Free-Modus: ebenfalls ganzer Bildschirm
    val captcha by downloadVm.captcha.collectAsStateWithLifecycle()
    captcha?.let { request ->
        CaptchaScreen(
            pageUrl = request.page.url,
            cookieUrl = request.page.cookieUrl,
            cookies = request.page.cookies,
            hosterName = request.hoster.displayName,
            siteHosts = request.hoster.siteHosts,
            isDirectDownloadUrl = request.hoster::isDirectDownloadUrl,
            onCancel = { downloadVm.cancelCaptcha() },
            onDirectLink = { url, cookies -> downloadVm.completeCaptcha(url, cookies) }
        )
        return
    }

    Scaffold(
        // Die Insets behandeln die inneren Bildschirme (eigene TopAppBar) und
        // die NavigationBar selbst - sonst kaeme der Abstand doppelt.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { JdSnackbarHost(messageHost) },
        // Plus-Knopf in der aeusseren Scaffold: so schiebt die Snackbar den
        // Knopf nach oben statt ihn zu verdecken.
        floatingActionButton = {
            when (tab) {
                Tab.Collector -> FloatingActionButton(
                    onClick = { addLinksPrefill = ""; showAddLinks = true }
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.accounts_add_links)) }
                Tab.Accounts -> FloatingActionButton(
                    onClick = { showAddAccount = true }
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.accounts_add_title)) }
                else -> {}
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val label = stringResource(t.labelRes)
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Downloads -> JdIcons.Download
                                    Tab.Collector -> JdIcons.Link
                                    Tab.Accounts -> Icons.Default.Person
                                    Tab.Settings -> Icons.Default.Settings
                                },
                                contentDescription = label
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        // Zustand je Tab (Suche, Filter, zugeklappte Pakete, Scrollposition)
        // beim Tabwechsel behalten statt jedes Mal neu aufzubauen
        val holder = rememberSaveableStateHolder()
        holder.SaveableStateProvider(tab.name) {
            when (tab) {
                Tab.Downloads -> DownloadsScreen(downloadVm, modifier)
                Tab.Collector -> LinkGrabberScreen(downloadVm, modifier)
                Tab.Accounts -> AccountsScreen(
                    accountVm,
                    showAdd = showAddAccount,
                    onShowAddChange = { showAddAccount = it },
                    modifier = modifier
                )
                Tab.Settings -> SettingsScreen(modifier)
            }
        }
    }

    if (showAddLinks) {
        // key: neue Vorbelegung (geteilter Text) setzt den Dialogtext zurueck
        key(addLinksPrefill) {
            AddLinksDialog(
                initialText = addLinksPrefill,
                onDismiss = { showAddLinks = false },
                onAdd = { text, pkg ->
                    // Meldungen ueber die Ressourcen aufloesen: der Rueckruf
                    // laeuft ausserhalb der Komposition
                    downloadVm.addLinks(text, pkg) { added, toCollector ->
                        if (added > 0) {
                            AppMessages.success(
                                resources.getQuantityString(
                                    if (toCollector) R.plurals.accounts_links_collected
                                    else R.plurals.accounts_links_started,
                                    added, added
                                )
                            )
                            if (!toCollector) tab = Tab.Downloads
                        } else {
                            AppMessages.info(resources.getString(R.string.accounts_no_new_links))
                        }
                    }
                }
            )
        }
    }
}
