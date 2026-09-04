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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jdandroid.CrashReporter
import com.jdandroid.JdApp
import com.jdandroid.container.ContainerFiles
import com.jdandroid.engine.DownloadService
import kotlinx.coroutines.launch

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
        handleIntent(intent)
        // Click'n'Load ueberlebt keinen Prozessneustart: ist es aktiviert, muss
        // der Dienst beim App-Start wieder hochgefahren werden, sonst lauscht
        // niemand auf dem Port obwohl der Schalter an steht.
        lifecycleScope.launch {
            if ((application as JdApp).settings.currentClickNLoadEnabled()) {
                DownloadService.send(this@MainActivity, DownloadService.ACTION_START_CNL)
            }
        }
        setContent {
            val settings = (application as JdApp).settings
            val modeKey by settings.themeMode.collectAsState(initial = "system")
            val dynamic by settings.dynamicColors.collectAsState(initial = false)
            val mode = ThemeMode.fromKey(modeKey)
            val dark = isDarkFor(mode)
            // Systemleisten zur gewaehlten Helligkeit passend einfaerben - auch
            // wenn der Modus manuell vom System abweicht.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
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
            // Groessenbegrenzt lesen: der Filter nimmt jede octet-stream-Datei an,
            // ein versehentlich geteiltes Video darf keinen OutOfMemoryError ausloesen.
            val result = runCatching { ContainerFiles.readText(contentResolver, uri) }
            val content = result.getOrNull()
            if (content != null && ContainerFiles.looksLikeDlc(content)) {
                dlcContent.value = content
            } else {
                val reason = result.exceptionOrNull()?.message
                    ?: "Die geöffnete Datei ist kein DLC-Container."
                AppMessages.error(reason)
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
    var tab by remember { mutableStateOf(Tab.Downloads) }
    val context = LocalContext.current
    // Ein DLC gehoert in den Linksammler: dorthin wechseln, die Meldung erscheint dort
    LaunchedEffect(dlcContent) { if (dlcContent != null) tab = Tab.Collector }

    // Zentrale Meldungen (DLC-Import, Kontofehler, ...): eine Fortschrittsmeldung
    // bleibt stehen, bis die naechste Meldung sie abloest.
    val messageHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        AppMessages.events.collect { message ->
            messageHost.currentSnackbarData?.dismiss()
            launch { messageHost.showSnackbar(message) }
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
                onLinksCollected = { tab = Tab.Collector }, modifier = modifier
            )
            Tab.Collector -> LinkGrabberScreen(downloadVm, dlcContent, onDlcConsumed, modifier)
            Tab.Accounts -> AccountsScreen(accountVm, modifier)
            Tab.Settings -> SettingsScreen(modifier)
        }
    }
}
