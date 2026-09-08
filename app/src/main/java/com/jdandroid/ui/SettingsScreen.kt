package com.jdandroid.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.container.ClickNLoadServer
import com.jdandroid.container.CnlStatus
import com.jdandroid.core.formatBytes
import com.jdandroid.data.NfsSettings
import com.jdandroid.data.SettingsRepository
import com.jdandroid.data.SettingsValues
import com.jdandroid.engine.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormatSymbols
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val settings = (context.applicationContext as JdApp).settings
    val scope = rememberCoroutineScope()
    val values by vm.values.collectAsStateWithLifecycle()

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
        containerColor = jdScaffoldColor(),
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, colors = jdTopBarColors()) }
    ) { padding ->
        val s = values ?: return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                // Keeps the focused field above the keyboard.
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AppearanceSection(settings, s)

            Spacer(Modifier.height(16.dp))
            DownloadSection(settings, s, onChooseFolder = { folderPicker.launch(null) })

            Spacer(Modifier.height(12.dp))
            NfsSection(settings, s, vm)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            ExtractSection(settings, s)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            ClickNLoadSection(settings, s)
            BackupSection()

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

@Composable
private fun AppearanceSection(settings: SettingsRepository, s: SettingsValues) {
    val scope = rememberCoroutineScope()
    val themeKey = s.themeMode
    SectionTitle(stringResource(R.string.settings_section_appearance))
    SettingsGroup {
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
}

/** Limits, connection switches and the target folder. */
@Composable
private fun DownloadSection(settings: SettingsRepository, s: SettingsValues, onChooseFolder: () -> Unit) {
    val scope = rememberCoroutineScope()
    val export = s.exportToDownloads
    val wifiOnly = s.wifiOnly
    val autoStart = s.autoStartLinks
    val freeMode = s.freeMode
    val treeUri = s.downloadTreeUri

    // Prefilled from the stored values once; edits survive rotation and tab switches.
    var maxConcurrentText by rememberSaveable { mutableStateOf(s.maxConcurrent.toString()) }
    var speedLimitText by rememberSaveable { mutableStateOf(SpeedLimitInput.format(s.speedLimitMbit)) }
    LaunchedEffect(s.maxConcurrent) { maxConcurrentText = SettingsFieldSync.maxConcurrent(maxConcurrentText, s.maxConcurrent) }
    LaunchedEffect(s.speedLimitMbit) { speedLimitText = SettingsFieldSync.speedLimit(speedLimitText, s.speedLimitMbit) }

    SectionTitle(stringResource(R.string.settings_section_downloads))
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = maxConcurrentText,
        onValueChange = { value ->
            maxConcurrentText = value.filter { it.isDigit() }.take(2)
            maxConcurrentText.toIntOrNull()?.let { n ->
                if (n in 1..99) scope.launch { settings.setMaxConcurrent(n) }
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
            val cleaned = SpeedLimitInput.clean(value)
            speedLimitText = cleaned
            SpeedLimitInput.parse(cleaned)?.let { n -> scope.launch { settings.setSpeedLimitMbit(n) } }
        },
        label = { Text(stringResource(R.string.settings_speed_limit_label)) },
        supportingText = {
            val bytes = SpeedLimitInput.parse(speedLimitText)?.let { SettingsRepository.mbitToBytesPerSecond(it) } ?: 0L
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
        TextButton(onClick = onChooseFolder) { Text(stringResource(R.string.settings_choose_folder)) }
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
}

/** Extraction switches plus the password and exclude lists. */
@Composable
private fun ExtractSection(settings: SettingsRepository, s: SettingsValues) {
    val scope = rememberCoroutineScope()
    val autoExtract = s.autoExtract
    val deleteArchive = s.deleteArchiveAfterExtract
    val flatExtract = s.flatExtract
    val removeLinks = s.removeLinksAfterExtract
    val excludeText = s.extractExcludeList
    val excludes = remember(excludeText) {
        excludeText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    val passwordText = s.passwordList
    val passwords = remember(passwordText) {
        passwordText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

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
}

/** Click'n'Load switch with the live server status and self-test. */
@Composable
private fun ClickNLoadSection(settings: SettingsRepository, s: SettingsValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cnlEnabled = s.clickNLoadEnabled
    val cnlRunning by CnlStatus.running.collectAsStateWithLifecycle()
    val cnlError by CnlStatus.error.collectAsStateWithLifecycle()
    val cnlBoundTo by CnlStatus.boundTo.collectAsStateWithLifecycle()
    val cnlLast by CnlStatus.lastRequest.collectAsStateWithLifecycle()
    var cnlTest by remember { mutableStateOf<String?>(null) }

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
}

/** Collapsible NFS target: switch, connection fields and a single-line connection check. */
@Composable
private fun NfsSection(settings: SettingsRepository, s: SettingsValues, vm: SettingsViewModel) {
    val scope = rememberCoroutineScope()
    val probing by vm.nfsProbe.probing.collectAsStateWithLifecycle()
    val outcome by vm.nfsProbe.outcome.collectAsStateWithLifecycle()
    val wizard by vm.nfsWizard.state.collectAsStateWithLifecycle(initialValue = null)
    val nfs = s.nfs
    var expanded by rememberSaveable { mutableStateOf(false) }
    var serverText by rememberSaveable { mutableStateOf(nfs.server) }
    var exportText by rememberSaveable { mutableStateOf(nfs.export) }
    var subDirText by rememberSaveable { mutableStateOf(nfs.subDir) }
    var uidText by rememberSaveable { mutableStateOf(nfs.uid.toString()) }
    var gidText by rememberSaveable { mutableStateOf(nfs.gid.toString()) }
    LaunchedEffect(nfs) {
        serverText = SettingsFieldSync.text(serverText, nfs.server)
        exportText = SettingsFieldSync.text(exportText, nfs.export)
        subDirText = SettingsFieldSync.text(subDirText, nfs.subDir)
        uidText = SettingsFieldSync.id(uidText, nfs.uid, NfsSettings.DEFAULT_UID)
        gidText = SettingsFieldSync.id(gidText, nfs.gid, NfsSettings.DEFAULT_GID)
    }
    fun update(change: NfsSettings.() -> NfsSettings) {
        scope.launch { settings.setNfs(settings.currentNfs().change()) }
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
                scope.launch {
                    // The check must work before the switch is on, so probe with the fields as typed.
                    vm.nfsProbe.start(settings.currentNfs().copy(enabled = true))
                }
            }
        ) { Text(stringResource(R.string.settings_nfs_probe)) }
        if (probing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        TextButton(
            onClick = { scope.launch { vm.nfsWizard.start(settings.currentNfs()) } }
        ) { Text(stringResource(R.string.settings_nfs_wizard)) }
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
    wizard?.let { state ->
        NfsWizardDialog(
            state = state,
            runner = vm.nfsWizard,
            onFinish = { result ->
                serverText = result.server
                result.export?.let { exportText = it }
                result.subDir?.let { subDirText = it }
                update {
                    copy(
                        server = result.server,
                        export = result.export ?: export,
                        subDir = result.subDir ?: subDir
                    )
                }
                vm.nfsWizard.close()
            }
        )
    }
}

/**
 * The NAS wizard in three levels: found servers, exports of the tapped
 * server, folders below the tapped export. Every level shrinks its list so
 * the buttons stay visible; "back" walks one level up.
 */
@Composable
private fun NfsWizardDialog(
    state: NfsWizardState,
    runner: NfsWizardRunner,
    onFinish: (NfsWizardResult) -> Unit
) {
    val step = state.step
    val server = state.server
    AlertDialog(
        onDismissRequest = runner::close,
        modifier = Modifier.imePadding(),
        properties = KeyboardAwareDialog,
        title = {
            Text(
                when (step) {
                    NfsWizardStep.SERVER -> stringResource(R.string.settings_nfs_discover_title)
                    NfsWizardStep.EXPORT -> stringResource(R.string.settings_nfs_exports_title, server?.name ?: server?.host ?: "")
                    NfsWizardStep.FOLDER -> stringResource(R.string.settings_nfs_browser_title)
                }
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_nfs_wizard_step, step.ordinal + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                when (step) {
                    NfsWizardStep.SERVER -> NfsServerLevel(state.discovery ?: NfsDiscoveryState(), runner)
                    NfsWizardStep.EXPORT -> NfsExportLevel(state.discovery ?: NfsDiscoveryState(), server?.host ?: "", runner)
                    NfsWizardStep.FOLDER -> NfsFolderLevel(state.export ?: "", state.browser ?: NfsBrowserState(), runner)
                }
            }
        },
        confirmButton = {
            when (step) {
                NfsWizardStep.SERVER -> TextButton(
                    enabled = state.discovery?.searching != true,
                    onClick = runner::search
                ) { Text(stringResource(R.string.settings_nfs_discover_again)) }
                NfsWizardStep.EXPORT -> TextButton(
                    onClick = { runner.result(null)?.let(onFinish) }
                ) { Text(stringResource(R.string.settings_nfs_exports_use_server)) }
                NfsWizardStep.FOLDER -> {
                    val browser = state.browser
                    TextButton(
                        enabled = browser != null && !browser.loading && browser.error == null,
                        onClick = { runner.result(browser?.path ?: "")?.let(onFinish) }
                    ) { Text(stringResource(R.string.settings_nfs_browser_choose)) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = runner::close) { Text(stringResource(R.string.common_cancel)) }
            if (step != NfsWizardStep.SERVER) {
                TextButton(onClick = runner::back) { Text(stringResource(R.string.settings_nfs_exports_back)) }
            }
        }
    )
}

@Composable
private fun ColumnScope.NfsServerLevel(state: NfsDiscoveryState, runner: NfsWizardRunner) {
    if (state.searching) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.settings_nfs_discover_searching), style = MaterialTheme.typography.bodySmall)
        }
    } else if (state.servers.isEmpty()) {
        Text(
            stringResource(R.string.settings_nfs_discover_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        items(state.servers, key = { it.host }) { server ->
            NfsBrowserRow(
                icon = JdIcons.Storage,
                name = server.name ?: server.host,
                detail = if (server.name == null) null else server.host,
                enabled = true,
                contentDescription = null,
                onClick = { runner.selectServer(server) }
            )
        }
    }
}

@Composable
private fun ColumnScope.NfsExportLevel(state: NfsDiscoveryState, host: String, runner: NfsWizardRunner) {
    Text(host, style = MaterialTheme.typography.bodyMedium)
    state.error?.let { error ->
        Text(
            if (error.unreachable) stringResource(R.string.settings_nfs_probe_unreachable, error.message)
            else error.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(4.dp))
    if (state.loadingExports) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (state.exports.isEmpty() && state.error == null) {
        Text(
            stringResource(R.string.settings_nfs_exports_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        items(state.exports, key = { it }) { path ->
            NfsBrowserRow(
                icon = JdIcons.Folder,
                name = path,
                detail = null,
                enabled = true,
                contentDescription = null,
                onClick = { runner.selectExport(path) }
            )
        }
    }
}

/**
 * Folders open on tap, files are shown greyed out with their size, a new
 * folder can be created below the current one.
 */
@Composable
private fun ColumnScope.NfsFolderLevel(export: String, state: NfsBrowserState, runner: NfsWizardRunner) {
    var newFolderOpen by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val ready = !state.loading && state.error == null
    val canCreate = ready && NfsSettings.isValidName(newFolderName.trim())
    fun create() {
        if (!canCreate) return
        keyboard?.hide()
        runner.createFolder(newFolderName)
        newFolderName = ""
        newFolderOpen = false
    }
    Text(
        NfsSettings.normalizePath("$export/${state.path}"),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    state.error?.let { error ->
        Text(
            if (error.unreachable) stringResource(R.string.settings_nfs_probe_unreachable, error.message)
            else error.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(4.dp))
    if (state.loading) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            if (state.path.isNotEmpty()) {
                item("..") {
                    NfsBrowserRow(
                        icon = JdIcons.FolderOpen,
                        name = "..",
                        detail = null,
                        enabled = true,
                        contentDescription = stringResource(R.string.settings_nfs_browser_parent),
                        onClick = runner::up
                    )
                }
            }
            if (state.entries.isEmpty() && state.error == null) {
                item("empty") {
                    Text(
                        stringResource(R.string.settings_nfs_browser_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            items(state.entries, key = { "e:" + it.name }) { entry ->
                NfsBrowserRow(
                    icon = if (entry.isDirectory) JdIcons.Folder else JdIcons.File,
                    name = entry.name,
                    detail = if (entry.isDirectory) null else formatBytes(entry.size),
                    enabled = entry.isDirectory,
                    contentDescription = null,
                    onClick = { runner.enter(entry.name) }
                )
            }
        }
    }
    if (newFolderOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newFolderName,
                onValueChange = { newFolderName = it },
                label = { Text(stringResource(R.string.settings_nfs_browser_folder_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { create() }),
                modifier = Modifier.weight(1f)
            )
            TextButton(enabled = canCreate, onClick = ::create) {
                Text(stringResource(R.string.settings_nfs_browser_create))
            }
        }
    } else {
        TextButton(enabled = ready, onClick = { newFolderOpen = true }) {
            Text(stringResource(R.string.settings_nfs_browser_new_folder))
        }
    }
}

@Composable
private fun NfsBrowserRow(
    icon: ImageVector,
    name: String,
    detail: String?,
    enabled: Boolean,
    contentDescription: String?,
    onClick: () -> Unit
) {
    val color = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = contentDescription, tint = color)
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            modifier = Modifier.weight(1f),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
    }
}

/** Input helpers of the NFS section; kept free of Compose for unit tests. */
internal object NfsSettingsUi {
    /** Keeps digits only; ids never exceed ten digits. */
    fun cleanId(text: String): String = text.filter { it.isDigit() }.take(10)

    /** Numeric uid/gid or [fallback] for empty or overflowing input. */
    fun parseId(text: String, fallback: Int): Int = text.trim().toIntOrNull()?.takeIf { it >= 0 } ?: fallback
}

/**
 * Keeps a prefilled text field in step with the stored value when the store changes
 * behind the field (backup restore). A field whose text already means the stored
 * value is left alone, so partial input such as "1," or a trailing space survives.
 */
internal object SettingsFieldSync {
    fun maxConcurrent(text: String, stored: Int): String =
        if (text.toIntOrNull() == stored) text else stored.toString()

    fun speedLimit(text: String, stored: Double, separator: Char? = null): String =
        if (SpeedLimitInput.parse(text) == stored) text
        else separator?.let { SpeedLimitInput.format(stored, it) } ?: SpeedLimitInput.format(stored)

    fun text(text: String, stored: String): String = if (text.trim() == stored) text else stored

    fun id(text: String, stored: Int, fallback: Int): String =
        if (NfsSettingsUi.parseId(text, fallback) == stored) text else stored.toString()
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.GAMER -> R.string.settings_theme_gamer
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
            modifier = Modifier.imePadding(),
            properties = KeyboardAwareDialog,
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

/** Text of the speed-limit field, with the decimal separator of the current locale. */
internal object SpeedLimitInput {
    private val defaultSeparator: Char
        get() = DecimalFormatSymbols.getInstance().decimalSeparator

    /** Integers without decimals, otherwise at most two decimals. */
    fun format(value: Double, separator: Char = defaultSeparator): String =
        if (value == Math.floor(value)) value.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.').replace('.', separator)

    /** Keeps digits and the first decimal separator (comma or period typed). */
    fun clean(text: String, separator: Char = defaultSeparator): String = buildString {
        var seen = false
        for (c in text) {
            if (c.isDigit()) append(c)
            else if ((c == ',' || c == '.') && !seen) { append(separator); seen = true }
        }
    }.take(8)

    /** Parses comma or period input; null for empty or incomplete text. */
    fun parse(text: String): Double? =
        text.trim().replace(',', '.').takeIf { it.isNotEmpty() && it != "." }?.toDoubleOrNull()
}
