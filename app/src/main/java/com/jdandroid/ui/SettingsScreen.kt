package com.jdandroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jdandroid.CrashReporter
import com.jdandroid.JdApp
import com.jdandroid.container.ClickNLoadServer
import com.jdandroid.container.CnlStatus
import com.jdandroid.engine.DownloadService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = (context.applicationContext as JdApp).settings
    val scope = rememberCoroutineScope()
    val export by settings.exportToDownloads.collectAsState(initial = true)
    val autoExtract by settings.autoExtract.collectAsState(initial = true)
    val deleteArchive by settings.deleteArchiveAfterExtract.collectAsState(initial = false)
    val removeLinks by settings.removeLinksAfterExtract.collectAsState(initial = true)
    val cnlEnabled by settings.clickNLoadEnabled.collectAsState(initial = false)
    val wifiOnly by settings.wifiOnly.collectAsState(initial = false)

    var passwords by remember { mutableStateOf("") }
    var maxConcurrentText by remember { mutableStateOf("") }
    var speedLimitText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        passwords = settings.passwordList.first()
        maxConcurrentText = settings.maxConcurrent.first().toString()
        speedLimitText = settings.speedLimitKbps.first().toString()
        loaded = true
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
            OutlinedTextField(
                value = maxConcurrentText,
                onValueChange = { value ->
                    maxConcurrentText = value.filter { it.isDigit() }.take(2)
                    maxConcurrentText.toIntOrNull()?.let { n ->
                        if (loaded && n in 1..99) scope.launch { settings.setMaxConcurrent(n) }
                    }
                },
                label = { Text("Gleichzeitige Downloads (1–99)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = speedLimitText,
                onValueChange = { value ->
                    speedLimitText = value.filter { it.isDigit() }.take(7)
                    speedLimitText.toIntOrNull()?.let { n ->
                        if (loaded) scope.launch { settings.setSpeedLimitKbps(n) }
                    }
                },
                label = { Text("Geschwindigkeitslimit (KiB/s, 0 = unbegrenzt)") },
                supportingText = { Text("Gilt gemeinsam für alle laufenden Downloads") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = "Nur über WLAN laden",
                subtitle = "Downloads pausieren bei mobiler Verbindung und starten " +
                    "automatisch, sobald WLAN verfügbar ist",
                checked = wifiOnly,
                onChange = { v -> scope.launch { settings.setWifiOnly(v) } }
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
            SettingSwitch(
                title = "Einträge nach dem Entpacken entfernen",
                subtitle = "Alle Teile des Archivs verschwinden aus der Download-Liste, " +
                    "sobald es erfolgreich entpackt wurde (wie im JDownloader)",
                checked = removeLinks,
                onChange = { v -> scope.launch { settings.setRemoveLinksAfterExtract(v) } }
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
                    if (loaded) scope.launch { settings.setPasswordList(value) }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                // Wie die Browser-Adresszeile: keine Autokorrektur und kein
                // automatisches Leerzeichen nach einem Punkt - Passwoerter
                // duerfen nicht "korrigiert" werden.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false
                ),
                placeholder = { Text("passwort1\npasswort2\n…") }
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Container & Click'n'Load", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = "Click'n'Load aktivieren",
                subtitle = "Lokaler Server auf Port ${ClickNLoadServer.PORT}. " +
                    "Browser auf diesem Gerät können Links direkt hierher senden.",
                checked = cnlEnabled,
                onChange = { v ->
                    scope.launch {
                        settings.setClickNLoadEnabled(v)
                        val action = if (v) DownloadService.ACTION_START_CNL
                        else DownloadService.ACTION_STOP_CNL
                        DownloadService.send(context, action)
                    }
                }
            )
            val cnlRunning by CnlStatus.running.collectAsState()
            val cnlError by CnlStatus.error.collectAsState()
            val cnlBoundTo by CnlStatus.boundTo.collectAsState()
            Text(
                when {
                    cnlRunning -> "Status: Server läuft auf Port " +
                        "${ClickNLoadServer.PORT} (${cnlBoundTo.orEmpty()}) " +
                        "und nimmt Links entgegen."
                    cnlError != null -> "Status: Start fehlgeschlagen – $cnlError"
                    cnlEnabled -> "Status: Server wird gestartet …"
                    else -> "Status: ausgeschaltet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    cnlRunning -> MaterialTheme.colorScheme.primary
                    cnlError != null -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "DLC-Dateien lassen sich über \"Öffnen mit\" bzw. Teilen importieren. " +
                    "Die Entschlüsselung nutzt den JDownloader-DLC-Dienst.",
                style = MaterialTheme.typography.bodySmall
            )

            // Diagnose: letzter Absturz, damit ein Fehler auf dem Geraet
            // nachvollziehbar ist statt nur "die App stuerzt ab".
            var crash by remember { mutableStateOf(CrashReporter.lastCrash(context)) }
            crash?.let { report ->
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text("Letzter Absturz", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        report.take(4000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Absturz", report))
                    }) { Text("Kopieren") }
                    TextButton(onClick = {
                        CrashReporter.clear(context)
                        crash = null
                    }) { Text("Löschen") }
                }
            }

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
