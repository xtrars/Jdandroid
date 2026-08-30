package com.jdandroid.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jdandroid.JdApp
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
            val darkTheme = isSystemInDarkTheme()
            val context = LocalContext.current
            // Material You (dynamische Farben) ab Android 12, sonst Standardpalette;
            // hell/dunkel folgt automatisch dem System.
            val colorScheme = when {
                Build.VERSION.SDK_INT >= 31 && darkTheme -> dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface {
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
            val looksLikeDlc = (uri.toString().endsWith(".dlc", true)) ||
                intent.type == "application/octet-stream"
            val content = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (content != null && (looksLikeDlc || content.trim().endsWith("=="))) {
                dlcContent.value = content
            }
        }
    }
}

private enum class Tab(val label: String) { Downloads("Downloads"), Accounts("Konten"), Settings("Einstellungen") }

@Composable
fun MainScreen(
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
    dlcContent: String?,
    onDlcConsumed: () -> Unit
) {
    var tab by remember { mutableStateOf(Tab.Downloads) }
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
                downloadVm, sharedText, onSharedTextConsumed, dlcContent, onDlcConsumed, modifier
            )
            Tab.Accounts -> AccountsScreen(accountVm, modifier)
            Tab.Settings -> SettingsScreen(modifier)
        }
    }
}
