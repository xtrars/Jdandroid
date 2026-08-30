package com.jdandroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jdandroid.JdApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = (context.applicationContext as JdApp).settings
    val scope = rememberCoroutineScope()
    val maxConcurrent by settings.maxConcurrent.collectAsState(initial = 2)
    val export by settings.exportToDownloads.collectAsState(initial = true)
    val autoExtract by settings.autoExtract.collectAsState(initial = true)
    val deleteArchive by settings.deleteArchiveAfterExtract.collectAsState(initial = false)

    var passwords by remember { mutableStateOf("") }
    var passwordsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        passwords = settings.passwordList.first()
        passwordsLoaded = true
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Einstellungen") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Downloads", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Gleichzeitige Downloads: $maxConcurrent", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = maxConcurrent.toFloat(),
                onValueChange = { v -> scope.launch { settings.setMaxConcurrent(v.toInt()) } },
                valueRange = 1f..6f,
                steps = 4
            )
            SettingSwitch(
                title = "In öffentlichen Download-Ordner",
                subtitle = "Fertige Dateien nach Downloads/JDAndroid verschieben",
                checked = export,
                onChange = { v -> scope.launch { settings.setExportToDownloads(v) } }
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Entpacken", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = "Archive automatisch entpacken",
                subtitle = "ZIP, 7z und RAR nach dem Download entpacken (auch mehrteilige Archive)",
                checked = autoExtract,
                onChange = { v -> scope.launch { settings.setAutoExtract(v) } }
            )
            SettingSwitch(
                title = "Archiv nach dem Entpacken löschen",
                subtitle = "Spart Speicherplatz, Original-Archiv wird entfernt",
                checked = deleteArchive,
                onChange = { v -> scope.launch { settings.setDeleteArchiveAfterExtract(v) } }
            )
            Spacer(Modifier.height(12.dp))
            Text("Passwortliste", style = MaterialTheme.typography.titleSmall)
            Text(
                "Ein Passwort pro Zeile. Beim Entpacken werden alle Passwörter " +
                    "der Reihe nach ausprobiert.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = passwords,
                onValueChange = { value ->
                    passwords = value
                    if (passwordsLoaded) scope.launch { settings.setPasswordList(value) }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = { Text("passwort1\npasswort2\n…") }
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "Unterstützte Hoster: Rapidgator, 1fichier, ddownload.\n" +
                    "Downloads laufen über die offiziellen APIs der Hoster und benötigen " +
                    "einen Premium-Account bzw. API-Key (Tab \"Konten\").",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
