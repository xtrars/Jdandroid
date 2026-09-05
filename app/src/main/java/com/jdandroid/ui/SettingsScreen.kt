package com.jdandroid.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.core.formatBytes
import com.jdandroid.container.ClickNLoadServer
import com.jdandroid.container.CnlStatus
import com.jdandroid.engine.DownloadService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = (context.applicationContext as JdApp).settings
    val scope = rememberCoroutineScope()
    val export by settings.exportToDownloads.collectAsStateWithLifecycle(initialValue = true)
    val autoExtract by settings.autoExtract.collectAsStateWithLifecycle(initialValue = true)
    val deleteArchive by settings.deleteArchiveAfterExtract.collectAsStateWithLifecycle(initialValue = true)
    val flatExtract by settings.flatExtract.collectAsStateWithLifecycle(initialValue = true)
    val removeLinks by settings.removeLinksAfterExtract.collectAsStateWithLifecycle(initialValue = true)
    val cnlEnabled by settings.clickNLoadEnabled.collectAsStateWithLifecycle(initialValue = false)
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle(initialValue = false)
    val autoStart by settings.autoStartLinks.collectAsStateWithLifecycle(initialValue = false)
    val freeMode by settings.freeMode.collectAsStateWithLifecycle(initialValue = true)
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
            speedLimitText = formatMbit(settings.speedLimitMbit.first())
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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, colors = jdTopBarColors()) }
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
            SectionTitle(stringResource(R.string.settings_section_appearance))
            SettingsGroup {
                val themeKey by settings.themeMode.collectAsStateWithLifecycle(initialValue = "system")
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.settings_theme_label), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = themeKey == m.key,
                            onClick = { scope.launch { settings.setThemeMode(m.key) } },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                        ) { Text(stringResource(m.labelRes())) }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.settings_section_downloads))
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = maxConcurrentText,
                onValueChange = { value ->
                    maxConcurrentText = value.filter { it.isDigit() }.take(2)
                    maxConcurrentText.toIntOrNull()?.let { n ->
                        if (loaded && n in 1..99) scope.launch { settings.setMaxConcurrent(n) }
                    }
                },
                label = { Text(stringResource(R.string.settings_max_concurrent_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = speedLimitText,
                onValueChange = { value ->
                    // Ziffern und ein Dezimaltrenner (Komma oder Punkt)
                    val cleaned = buildString {
                        var separator = false
                        for (c in value) {
                            if (c.isDigit()) append(c)
                            else if ((c == ',' || c == '.') && !separator) { append(','); separator = true }
                        }
                    }.take(8)
                    speedLimitText = cleaned
                    parseMbit(cleaned)?.let { n ->
                        if (loaded) scope.launch { settings.setSpeedLimitMbit(n) }
                    }
                },
                label = { Text(stringResource(R.string.settings_speed_limit_label)) },
                supportingText = {
                    val bytes = parseMbit(speedLimitText)
                        ?.let { com.jdandroid.data.SettingsRepository.mbitToBytesPerSecond(it) } ?: 0L
                    Text(
                        if (bytes > 0) stringResource(R.string.settings_speed_limit_hint_bytes, formatBytes(bytes))
                        else stringResource(R.string.settings_speed_limit_hint)
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = stringResource(R.string.settings_auto_start_title),
                subtitle = stringResource(R.string.settings_auto_start_subtitle),
                checked = autoStart,
                onChange = { v -> scope.launch { settings.setAutoStartLinks(v) } }
            )
            SettingSwitch(
                title = stringResource(R.string.settings_wifi_only_title),
                subtitle = stringResource(R.string.settings_wifi_only_subtitle),
                checked = wifiOnly,
                onChange = { v -> scope.launch { settings.setWifiOnly(v) } }
            )
            SettingSwitch(
                title = stringResource(R.string.settings_free_mode_title),
                subtitle = stringResource(R.string.settings_free_mode_subtitle),
                checked = freeMode,
                onChange = { v -> scope.launch { settings.setFreeMode(v) } }
            )

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_target_folder), style = MaterialTheme.typography.titleSmall)
            Text(
                treeUri?.let {
                    stringResource(
                        R.string.settings_target_folder_chosen,
                        displayTree(it).ifBlank { stringResource(R.string.settings_target_folder_main_storage) }
                    )
                } ?: stringResource(
                    if (export) R.string.settings_target_folder_default_public
                    else R.string.settings_target_folder_default_private
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.settings_choose_folder)) }
                if (treeUri != null) {
                    TextButton(onClick = { scope.launch { settings.setDownloadTreeUri(null) } }) {
                        Text(stringResource(R.string.settings_reset))
                    }
                }
            }
            if (treeUri == null) {
                SettingSwitch(
                    title = stringResource(R.string.settings_export_title),
                    subtitle = stringResource(R.string.settings_export_subtitle),
                    checked = export,
                    onChange = { v -> scope.launch { settings.setExportToDownloads(v) } }
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle(stringResource(R.string.common_extract))
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = stringResource(R.string.settings_auto_extract_title),
                subtitle = stringResource(R.string.settings_auto_extract_subtitle),
                checked = autoExtract,
                onChange = { v -> scope.launch { settings.setAutoExtract(v) } }
            )
            SettingSwitch(
                title = stringResource(R.string.settings_flat_extract_title),
                subtitle = stringResource(R.string.settings_flat_extract_subtitle),
                checked = flatExtract,
                onChange = { v -> scope.launch { settings.setFlatExtract(v) } }
            )
            SettingSwitch(
                title = stringResource(R.string.settings_delete_archive_title),
                subtitle = stringResource(R.string.settings_delete_archive_subtitle),
                checked = deleteArchive,
                onChange = { v -> scope.launch { settings.setDeleteArchiveAfterExtract(v) } }
            )
            SettingSwitch(
                title = stringResource(R.string.settings_remove_entries_title),
                subtitle = stringResource(R.string.settings_remove_entries_subtitle),
                checked = removeLinks,
                onChange = { v -> scope.launch { settings.setRemoveLinksAfterExtract(v) } }
            )
            Spacer(Modifier.height(12.dp))
            StringListEditor(
                title = stringResource(R.string.settings_passwords_title),
                description = stringResource(R.string.settings_passwords_description),
                emptyText = stringResource(R.string.settings_passwords_empty),
                fieldLabel = stringResource(R.string.settings_passwords_field_label),
                importTitle = stringResource(R.string.settings_passwords_import_title),
                importPlaceholder = stringResource(R.string.settings_passwords_import_placeholder),
                removeDescription = stringResource(R.string.settings_passwords_remove),
                items = passwords,
                onAdd = { list -> scope.launch { settings.addPasswords(list) } },
                onRemove = { pw -> scope.launch { settings.removePassword(pw) } }
            )
            Spacer(Modifier.height(16.dp))
            StringListEditor(
                title = stringResource(R.string.settings_excludes_title),
                description = stringResource(
                    R.string.settings_excludes_description,
                    // Beispielmuster als Liste, damit die Uebersetzung sie nicht abtippen muss
                    stringArrayResource(R.array.settings_exclude_examples).joinToString(", ")
                ),
                emptyText = stringResource(R.string.settings_excludes_empty),
                fieldLabel = stringResource(R.string.settings_excludes_field_label),
                importTitle = stringResource(R.string.settings_excludes_import_title),
                importPlaceholder = stringResource(R.string.settings_excludes_import_placeholder),
                removeDescription = stringResource(R.string.settings_excludes_remove),
                items = excludes,
                onAdd = { list -> scope.launch { settings.addExtractExcludes(list) } },
                onRemove = { pattern -> scope.launch { settings.removeExtractExclude(pattern) } }
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionTitle(stringResource(R.string.settings_section_cnl))
            Spacer(Modifier.height(8.dp))
            SettingSwitch(
                title = stringResource(R.string.settings_cnl_title),
                subtitle = stringResource(R.string.settings_cnl_subtitle, ClickNLoadServer.PORT),
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
            val cnlLast by CnlStatus.lastRequest.collectAsStateWithLifecycle()
            var cnlTest by remember { mutableStateOf<String?>(null) }
            Text(
                when {
                    cnlRunning -> stringResource(
                        R.string.settings_cnl_status_running, ClickNLoadServer.PORT, cnlBoundTo.orEmpty()
                    )
                    cnlError != null -> stringResource(R.string.settings_cnl_status_failed, cnlError.orEmpty())
                    cnlEnabled -> stringResource(R.string.settings_cnl_status_starting)
                    else -> stringResource(R.string.settings_cnl_status_off)
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    cnlRunning -> MaterialTheme.colorScheme.primary
                    cnlError != null -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (cnlRunning) {
                Text(
                    cnlLast?.let { stringResource(R.string.settings_cnl_last_request, it) }
                        ?: stringResource(R.string.settings_cnl_no_request),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val testingText = stringResource(R.string.settings_cnl_testing)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        cnlTest = testingText
                        scope.launch {
                            cnlTest = withContext(Dispatchers.IO) { ClickNLoadServer.selfTest() }
                        }
                    }) { Text(stringResource(R.string.settings_cnl_test)) }
                    cnlTest?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    stringResource(R.string.settings_cnl_chrome_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_dlc_hint),
                style = MaterialTheme.typography.bodySmall
            )


            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.settings_hosters_hint),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            // Installierte Version, damit bei Rueckfragen klar ist, welche APK laeuft
            val packageInfo = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            }
            val version = packageInfo?.let {
                stringResource(
                    R.string.settings_version,
                    it.versionName ?: "",
                    androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)
                )
            } ?: "JDAndroid"
            Text(version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Anzeigetext der Hell/Dunkel-Auswahl in der Gerätesprache. */
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

/**
 * Lesbarer Name eines SAF-Ordners: "primary:Download/JD" -> "Download/JD".
 * Leer, wenn der Hauptspeicher selbst gewaehlt ist (Aufrufer setzt den Text).
 */
private fun displayTree(uri: String): String {
    val decoded = runCatching { Uri.decode(uri) }.getOrDefault(uri)
    val tree = decoded.substringAfter("/tree/", decoded)
    return tree.substringAfter(':', tree)
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
    // Zusammengeklappt, bis man die Liste braucht: die Einstellungen bleiben
    // uebersichtlich, die Anzahl der Eintraege ist trotzdem sichtbar
    var expanded by rememberSaveable(title) { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (passwords.isEmpty()) stringResource(R.string.settings_list_empty)
                else pluralStringResource(R.plurals.settings_list_count, passwords.size, passwords.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(if (expanded) R.string.settings_collapse else R.string.settings_expand)
        )
    }
    if (!expanded) return
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
        ) { Text(stringResource(R.string.settings_add)) }
    }
    TextButton(onClick = { importOpen = true }) { Text(stringResource(R.string.settings_import_multiple)) }

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
                ) { Text(stringResource(R.string.settings_apply)) }
            },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text(stringResource(R.string.common_cancel)) } }
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

/** Mbit/s fuer das Eingabefeld: ganze Zahl ohne Nachkommastellen, sonst mit Komma. */
private fun formatMbit(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString()
    else String.format(java.util.Locale.GERMANY, "%.2f", value).trimEnd('0').trimEnd(',')

/** Eingabe mit Komma oder Punkt lesen; null bei leerem oder unvollstaendigem Text. */
private fun parseMbit(text: String): Double? =
    text.trim().replace(',', '.').takeIf { it.isNotEmpty() && it != "." }?.toDoubleOrNull()
