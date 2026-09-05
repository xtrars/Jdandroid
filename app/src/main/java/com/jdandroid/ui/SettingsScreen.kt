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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.core.formatBytes
import com.jdandroid.container.ClickNLoadServer
import com.jdandroid.container.CnlStatus
import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsProbe
import com.jdandroid.engine.nfs.NfsShares
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

    // Prefill from the stored values only once; edits survive rotation and tab switches.
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

    // The persisted permission lets the download service write to the folder.
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
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                // Keeps the focused field above the keyboard.
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
                    // Digits and a single decimal separator (comma or period).
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

            Spacer(Modifier.height(12.dp))
            NfsSection(settings)

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

/** Collapsible NFS target: switch, connection fields and a single-line connection check. */
@Composable
private fun NfsSection(settings: com.jdandroid.data.SettingsRepository) {
    val scope = rememberCoroutineScope()
    val nfs by settings.nfs.collectAsStateWithLifecycle(initialValue = NfsSettings())
    var expanded by rememberSaveable { mutableStateOf(false) }
    var serverText by rememberSaveable { mutableStateOf("") }
    var exportText by rememberSaveable { mutableStateOf("") }
    var subDirText by rememberSaveable { mutableStateOf("") }
    var uidText by rememberSaveable { mutableStateOf("") }
    var gidText by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<NfsProbeOutcome?>(null) }
    LaunchedEffect(Unit) {
        if (!loaded) {
            val current = settings.currentNfs()
            serverText = current.server
            exportText = current.export
            subDirText = current.subDir
            uidText = current.uid.toString()
            gidText = current.gid.toString()
            loaded = true
        }
    }
    fun update(change: NfsSettings.() -> NfsSettings) {
        if (loaded) scope.launch { settings.setNfs(settings.currentNfs().change()) }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_nfs_section), style = MaterialTheme.typography.titleSmall)
            Text(
                if (nfs.isUsable) stringResource(R.string.settings_nfs_summary_on, nfs.server, nfs.rootPath)
                else stringResource(R.string.settings_nfs_summary_off),
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

    SettingSwitch(
        title = stringResource(R.string.settings_nfs_enabled_title),
        subtitle = stringResource(R.string.settings_nfs_enabled_subtitle),
        checked = nfs.enabled,
        onChange = { v -> update { copy(enabled = v) } }
    )
    Spacer(Modifier.height(4.dp))
    val uriKeyboard = KeyboardOptions(
        keyboardType = KeyboardType.Uri,
        autoCorrectEnabled = false,
        imeAction = ImeAction.Next
    )
    OutlinedTextField(
        value = serverText,
        onValueChange = { serverText = it; update { copy(server = it.trim()) } },
        label = { Text(stringResource(R.string.settings_nfs_server)) },
        keyboardOptions = uriKeyboard,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = exportText,
        onValueChange = { exportText = it; update { copy(export = it.trim()) } },
        label = { Text(stringResource(R.string.settings_nfs_export)) },
        placeholder = { Text(stringResource(R.string.settings_nfs_export_placeholder)) },
        keyboardOptions = uriKeyboard,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = subDirText,
        onValueChange = { subDirText = it; update { copy(subDir = it.trim()) } },
        label = { Text(stringResource(R.string.settings_nfs_subdir)) },
        keyboardOptions = uriKeyboard,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = uidText,
            onValueChange = { v ->
                uidText = NfsSettingsUi.cleanId(v)
                update { copy(uid = NfsSettingsUi.parseId(uidText, NfsSettings.DEFAULT_UID)) }
            },
            label = { Text(stringResource(R.string.settings_nfs_uid)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = gidText,
            onValueChange = { v ->
                gidText = NfsSettingsUi.cleanId(v)
                update { copy(gid = NfsSettingsUi.parseId(gidText, NfsSettings.DEFAULT_GID)) }
            },
            label = { Text(stringResource(R.string.settings_nfs_gid)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_nfs_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = !probing && serverText.isNotBlank() && exportText.isNotBlank(),
            onClick = {
                probing = true
                outcome = null
                scope.launch {
                    // The check must work before the switch is on, so probe with the fields as typed.
                    val target = settings.currentNfs().copy(enabled = true)
                    outcome = NfsProbeOutcome.of(runCatching { NfsShares.probe(target) })
                    probing = false
                }
            }
        ) { Text(stringResource(R.string.settings_nfs_probe)) }
        if (probing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    outcome?.let { result ->
        Text(
            when (result) {
                is NfsProbeOutcome.Ok -> stringResource(
                    R.string.settings_nfs_probe_ok,
                    pluralStringResource(R.plurals.settings_nfs_probe_entries, result.entries, result.entries),
                    formatBytes(result.freeBytes),
                    formatBytes(result.totalBytes)
                )
                is NfsProbeOutcome.Unreachable -> stringResource(R.string.settings_nfs_probe_unreachable, result.message)
                is NfsProbeOutcome.Failed -> result.message
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (result is NfsProbeOutcome.Ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
}

/** Outcome of a connection check, reduced to what the single result line shows. */
internal sealed class NfsProbeOutcome {
    data class Ok(val entries: Int, val freeBytes: Long, val totalBytes: Long) : NfsProbeOutcome()
    /** NAS off or unreachable; shown with a "not reachable" prefix. */
    data class Unreachable(val message: String) : NfsProbeOutcome()
    data class Failed(val message: String) : NfsProbeOutcome()

    companion object {
        fun of(result: Result<NfsProbe>): NfsProbeOutcome = result.fold(
            onSuccess = { Ok(it.entries.size, it.freeBytes, it.totalBytes) },
            onFailure = { e ->
                val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                if (e is NfsFailure.Transient) Unreachable(message) else Failed(message)
            }
        )
    }
}

/** Input helpers of the NFS section; kept free of Compose for unit tests. */
internal object NfsSettingsUi {
    /** Keeps digits only; ids never exceed ten digits. */
    fun cleanId(text: String): String = text.filter { it.isDigit() }.take(10)

    /** Numeric uid/gid or [fallback] for empty or overflowing input. */
    fun parseId(text: String, fallback: Int): Int = text.trim().toIntOrNull()?.takeIf { it >= 0 } ?: fallback
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

/**
 * Readable name of a SAF tree: "primary:Download/JD" -> "Download/JD".
 * Empty when the storage root itself is selected.
 */
private fun displayTree(uri: String): String {
    val decoded = runCatching { Uri.decode(uri) }.getOrDefault(uri)
    val tree = decoded.substringAfter("/tree/", decoded)
    return tree.substringAfter(':', tree)
}

/** Editable string list (passwords, exclude patterns) with bulk import. */
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
    // Collapsed by default; the entry count stays visible.
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
            // Uri keyboard: no autocorrect, no space after a period.
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
    // The whole row toggles: larger target, and screen readers read title,
    // description and state as one element.
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

/** Mbit/s for the input field: integers without decimals, otherwise with a comma. */
private fun formatMbit(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString()
    else String.format(java.util.Locale.GERMANY, "%.2f", value).trimEnd('0').trimEnd(',')

/** Parses comma or period input; null for empty or incomplete text. */
private fun parseMbit(text: String): Double? =
    text.trim().replace(',', '.').takeIf { it.isNotEmpty() && it != "." }?.toDoubleOrNull()
