package com.jdandroid.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        sharedText.value = sharedTextFrom(intent)
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
                        onSharedTextConsumed = { sharedText.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedTextFrom(intent)?.let { sharedText.value = it }
    }

    private fun sharedTextFrom(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
}

private enum class Tab(val label: String) { Downloads("Downloads"), Accounts("Konten"), Settings("Einstellungen") }

@Composable
fun MainScreen(sharedText: String?, onSharedTextConsumed: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.Downloads) }
    val downloadVm: DownloadViewModel = viewModel()
    val accountVm: AccountViewModel = viewModel()

    Scaffold(
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
            Tab.Downloads -> DownloadsScreen(downloadVm, sharedText, onSharedTextConsumed, modifier)
            Tab.Accounts -> AccountsScreen(accountVm, modifier)
            Tab.Settings -> SettingsScreen(modifier)
        }
    }
}
