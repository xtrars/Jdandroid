package com.jdandroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val export by settings.exportToDownloads.collectAsStateWithLifecycle(initialValue = true)
    val autoExtract by settings.autoExtract.collectAsStateWithLifecycle(initialValue = true)
    val deleteArchive by settings.deleteArchiveAfterExtract.collectAsStateWithLifecycle(initialValue = false)
    val removeLinks by settings.removeLinksAfterExtract.collectAsStateWithLifecycle(initialValue = true)
    val cnlEnabled by settings.clickNLoadEnabled.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle(initialValue = false)
    val autoStart by settings.autoStartLinks.collectAsStateWithLifecycle(initialValue = false)
    val treeUri by settings.downloadTreeUri.collectAsStateWithLifecycle(initialValue = null)
    val excludeText by settings.extractExcludeList.collectAsStateWithLifecycle(initialValue = "")
    val excludes = remember(excludeText) {
        excludeText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    val passwordText by settings.passwordList.collectAsStateWithLifecycle(initialValue = "")
    val passwords = remember(passwordText) {
        passwordText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Eingaben ueberleben Drehen und Tabwechsel; nur beim ersten Aufbau aus
    // den gespeicherten Werten vorbelegen
    var maxConcurrentText by rememberSaveable { mutableStateOf("") }
    var speedLimitText by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            maxConcurrentText = settings.maxConcurrent.first().toString()
            speedLimitText = settings.speedLimitKbps.first().toString()
            loaded = true
        }
    }

    // Zielordner per Storage Access Framework (auch SD-Karte); die Berechtigung
    // wird dauerhaft uebernommen, damit der Download-Dienst dort schreiben darf.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        scope.launch { settings.setDownloadTreeUri(uri.toString()) }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Einstellungen") }, colors = jdTopBarColors()) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                // Seitliche Insets (Displayausschnitt, Querformat) freihalten
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                // Tastatur: das fokussierte Feld bleibt ueber der Tastatur sichtbar
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("Darstellung")
            SettingsGroup {
                val themeKey by settings.themeMode.collectAsStateWithLifecycle(initialValue = "system")
                val dynamic by settings.dynamicColors.collectAsStateWithLifecycle(initialValue = false)
                Spacer(Modifier.height(6.dp))
                Text("Hell / Dunkel", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = themeKey == m.key,
                            onClick = { scope.launch { settings.setThemeMode(m.key) } },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                        ) { Text(m.label) }
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    SettingSwitch(
                        title = "Farben vom Hintergrundbild (Material You)",
                        subtitle = "Aus: eigene Petrol-Palette der App",
                        checked = dynamic,
                        onChange = { v -> scope.launch { settings.setDynamicColors(v) } }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Downloads")
            Spacer(Modifier.height(4.dp))
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
                title = "Neue Links sofort starten",
                subtitle = "Aus: Links landen zuerst im Linksammler, werden online geprüft " +
                    "und starten erst auf \"Starten\" (wie im JDownloader)",
                checked = autoStart,
                onChange = { v -> scope.launch { settings.setAutoStartLinks(v) } }
            )
            SettingSwitch(
                title = "Nur über WLAN laden",
                subtitle = "Downloads pausieren bei mobiler Verbindung und starten " +
                    "automatisch, sobald WLAN verfügbar ist",
                checked = wifiOnly,
                onChange = { v -> scope.launch { settings.setWifiOnly(v) } }
            )

            Spacer(Modifier.height(12.dp))
            Text("Zielordner", style = MaterialTheme.typography.titleSmall)
            Text(
                treeUri?.let { "Gewählt: ${displayTree(it)}" }
                    ?: if (export) "Standard: Downloads/JDAndroid" else "Standard: App-Ordner (nicht öffentlich)",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { folderPicker.launch(null) }) { Text("Ordner wählen …") }
                if (treeUri != null) {
                    TextButton(onClick = { scope.launch { settings.setDownloadTreeUri(null) } }) {
                        Text("Zurücksetzen")
                    }
                }
            }
            if (treeUri == null) {
                SettingSwitch(
                    title = "In öffentlichen Download-Ordner",
                    subtitle = "Fertige Dateien nach Downloads/JDAndroid verschieben",
                    checked = export,
                    onChange = { v -> scope.launch { settings.setExportToDownloads(v) } }
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("Entpacken")
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
            StringListEditor(
                title = "Passwortliste",
                description = "Beim Entpacken werden alle Passwörter der Reihe nach ausprobiert. " +
                    "Passwörter aus Click'n'Load werden automatisch ergänzt.",
                emptyText = "Noch keine Passwörter.",
                fieldLabel = "Neues Passwort",
                importTitle = "Passwörter einfügen",
                importPlaceholder = "Ein Passwort pro Zeile",
                removeDescription = "Passwort entfernen",
                items = passwords,
                onAdd = { list -> scope.launch { settings.addPasswords(list) } },
                onRemove = { pw -> scope.launch { settings.removePassword(pw) } }
            )
            Spacer(Modifier.height(16.dp))
            StringListEditor(
                title = "Vom Entpacken ausschließen",
                description = "Dateien im Archiv, die zu diesen Mustern passen, werden nicht " +
                    "entpackt (wie im JDownloader). * steht für beliebige Zeichen, ? für " +
                    "eines, z.B. *.nfo, *.sfv, *sample*, proof/*",
                emptyText = "Keine Ausschlüsse – alles wird entpackt.",
                fieldLabel = "Neues Muster",
                importTitle = "Muster einfügen",
                importPlaceholder = "Ein Muster pro Zeile",
                removeDescription = "Muster entfernen",
                items = excludes,
                onAdd = { list -> scope.launch { settings.addExtractExcludes(list) } },
                onRemove = { pattern -> scope.launch { settings.removeExtractExclude(pattern) } }
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle("Container & Click'n'Load")
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
            val cnlRunning by CnlStatus.running.collectAsStateWithLifecycle()
            val cnlError by CnlStatus.error.collectAsStateWithLifecycle()
            val cnlBoundTo by CnlStatus.boundTo.collectAsStateWithLifecycle()
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
                SectionTitle("Letzter Absturz")
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
            Spacer(Modifier.height(12.dp))
            // Installierte Version, damit bei Rueckfragen klar ist, welche APK laeuft
            val version = remember {
                runCatching {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    "JDAndroid ${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
                }.getOrDefault("JDAndroid")
            }
            Text(version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Lesbarer Name eines SAF-Ordners: "primary:Download/JD" -> "Download/JD". */
private fun displayTree(uri: String): String {
    val decoded = runCatching { Uri.decode(uri) }.getOrDefault(uri)
    val tree = decoded.substringAfter("/tree/", decoded)
    return tree.substringAfter(':', tree).ifBlank { "Hauptspeicher" }
}

/**
 * Textliste (Passwoerter, Ausschlussmuster): ein Eintrag pro Zeile mit
 * Loeschen, neuer Eintrag per Feld, Sammel-Import fuer mehrere Zeilen.
 */
@Composable
private fun StringListEditor(
    title: String,
    description: String,
    emptyText: String,
    fieldLabel: String,
    importTitle: String,
    importPlaceholder: String,
    removeDescription: String,
    items: List<String>,
    onAdd: (List<String>) -> Unit,
    onRemove: (String) -> Unit
) {
    val passwords = items
    var newPassword by rememberSaveable { mutableStateOf("") }
    var importOpen by rememberSaveable { mutableStateOf(false) }

    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(description, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (passwords.isEmpty()) {
        Text(
            emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column {
                passwords.forEachIndexed { index, pw ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pw,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemove(pw) }) {
                            Icon(Icons.Default.Delete, contentDescription = removeDescription)
                        }
                    }
                    if (index < passwords.lastIndex) HorizontalDivider()
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text(fieldLabel) },
            singleLine = true,
            // Wie die Browser-Adresszeile: keine Autokorrektur, kein
            // automatisches Leerzeichen nach einem Punkt.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false
            ),
            modifier = Modifier.weight(1f)
        )
        TextButton(
            enabled = newPassword.isNotBlank(),
            onClick = { onAdd(listOf(newPassword.trim())); newPassword = "" }
        ) { Text("Hinzufügen") }
    }
    TextButton(onClick = { importOpen = true }) { Text("Mehrere einfügen …") }

    if (importOpen) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text(importTitle) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false
                    ),
                    placeholder = { Text(importPlaceholder) }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        onAdd(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
                        importOpen = false
                    }
                ) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    // Die ganze Zeile ist der Schalter: groessere Trefferflaeche, und
    // Screenreader lesen Titel, Beschreibung und Zustand als ein Element
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
