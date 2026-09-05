package com.jdandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jdandroid.R
import com.jdandroid.core.FreeMode
import com.jdandroid.core.formatBytes
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadNotes
import com.jdandroid.data.DownloadStatus
import com.jdandroid.hoster.HosterRegistry

private const val SEPARATOR = " · "

/**
 * Resolves stored note codes ([DownloadNotes], [FreeMode]) at display time;
 * foreign texts such as hoster messages pass through unchanged.
 */
@Composable
private fun noteText(note: String, retryAt: Long = 0L, now: Long = 0L): String = when (note) {
    DownloadNotes.WAITING_PARTS -> stringResource(R.string.downloads_waiting_for_parts)
    DownloadNotes.WAITING_WIFI -> stringResource(R.string.downloads_waiting_for_wifi)
    DownloadNotes.EXPORT_PENDING -> stringResource(R.string.downloads_waiting_for_nas)
    else -> FreeMode.displayText(note, retryAt, now) ?: note
}

@Composable
fun ConfirmDeleteDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

private enum class ListFilter(val label: Int, val matches: (DownloadItem) -> Boolean) {
    ALL(R.string.downloads_filter_all, { true }),
    ACTIVE(R.string.downloads_filter_active, { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.EXTRACTING }),
    WAITING(R.string.downloads_filter_waiting, { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }),
    DONE(R.string.downloads_filter_done, { it.status == DownloadStatus.COMPLETED }),
    FAILED(R.string.downloads_filter_failed, { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.OFFLINE })
}

/** Saves collapsed packages across rotation and tab switches as a list of ids. */
private val collapsedSaver = listSaver<SnapshotStateMap<Long, Boolean>, Long>(
    save = { map -> map.filterValues { it }.keys.toList() },
    restore = { ids -> mutableStateMapOf<Long, Boolean>().apply { ids.forEach { put(it, true) } } }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    vm: DownloadViewModel,
    modifier: Modifier = Modifier
) {
    val allGroups by vm.groups.collectAsStateWithLifecycle()
    val collapsed = rememberSaveable(saver = collapsedSaver) { mutableStateMapOf<Long, Boolean>() }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ListFilter.ALL) }

    // Registered after the MainActivity handler, so it takes precedence.
    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }

    // Packages without a matching item disappear.
    val groups = remember(allGroups, query, filter) {
        val q = query.trim().lowercase()
        allGroups.mapNotNull { g ->
            val items = g.items.filter { item ->
                filter.matches(item) && (
                    q.isEmpty() || g.pkg.name.lowercase().contains(q) ||
                        (item.fileName ?: item.url).lowercase().contains(q)
                    )
            }
            if (items.isEmpty()) null else g.copy(items = items)
        }
    }

    Scaffold(
        modifier = modifier,
        // The MainActivity's NavigationBar already handles the bottom inset.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                colors = jdTopBarColors(),
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                        Icon(
                            if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = stringResource(
                                if (searchOpen) R.string.downloads_search_close else R.string.downloads_search_open
                            )
                        )
                    }
                    TextButton(onClick = { vm.resumeAll() }) { Text(stringResource(R.string.downloads_start_all)) }
                    TextButton(onClick = { vm.pauseAll() }) { Text(stringResource(R.string.common_pause)) }
                }
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.downloads_search_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusRequester(searchFocus)
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ListFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(stringResource(f.label)) }
                )
            }
        }
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(
                        if (allGroups.isEmpty()) R.string.downloads_empty_hint
                        else R.string.downloads_empty_filter
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                groups.forEach { group ->
                    val isCollapsed = collapsed[group.pkg.id] ?: false
                    item(key = "pkg-${group.pkg.id}") {
                        PackageHeader(
                            group = group,
                            collapsed = isCollapsed,
                            onToggle = { collapsed[group.pkg.id] = !isCollapsed },
                            vm = vm
                        )
                    }
                    if (!isCollapsed) {
                        items(group.items, key = { it.id }) { item ->
                            DownloadRow(item, group.extractPercent(item), vm, Modifier.padding(start = 10.dp))
                        }
                    }
                    item(key = "gap-${group.pkg.id}") { Spacer(Modifier.height(6.dp)) }
                }
            }
        }
        }
    }

}

/** Package header: name, overall progress and package actions. */
@Composable
private fun PackageHeader(
    group: DownloadGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
    vm: DownloadViewModel
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    PackageCard {
        Column(Modifier.padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (collapsed) Icons.Default.KeyboardArrowRight
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(
                            if (collapsed) R.string.downloads_expand else R.string.downloads_collapse
                        )
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        group.pkg.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val parts = buildList {
                        add(pluralStringResource(R.plurals.downloads_file_count, group.items.size, group.items.size))
                        add(pluralStringResource(R.plurals.downloads_summary_finished, group.finished, group.finished))
                        if (group.failed > 0) add(pluralStringResource(R.plurals.downloads_summary_failed, group.failed, group.failed))
                        if (group.total > 0) {
                            add(
                                stringResource(
                                    R.string.downloads_bytes_of_total,
                                    formatBytes(group.done), formatBytes(group.total)
                                )
                            )
                        }
                        if (group.speed > 0) add(stringResource(R.string.downloads_speed, formatBytes(group.speed)))
                        if (group.extracting) {
                            add(
                                if (group.extractPercent >= 0) {
                                    stringResource(R.string.downloads_summary_extracting_percent, group.extractPercent)
                                } else stringResource(R.string.downloads_summary_extracting)
                            )
                        }
                        group.pkg.source?.let { add(stringResource(R.string.downloads_summary_source, it)) }
                    }
                    MetaRow(parts.joinToString(SEPARATOR))
                }
                if (group.pkg.id != 0L) {
                    // One direct action (start/pause); everything else goes in the menu.
                    IconButton(onClick = {
                        if (group.active) vm.pausePackage(group.pkg.id)
                        else vm.startPackage(group.pkg.id)
                    }) {
                        Icon(
                            if (group.active) JdIcons.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(
                                if (group.active) R.string.downloads_pause_package
                                else R.string.downloads_start_package
                            )
                        )
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.downloads_package_actions))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_menu_rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; renaming = true }
                            )
                            if (group.finished > 0) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.downloads_menu_extract_archives)) },
                                    leadingIcon = { Icon(JdIcons.Unarchive, contentDescription = null) },
                                    onClick = { menuOpen = false; vm.extractPackage(group.pkg.id) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_menu_delete_package)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; confirmDelete = true }
                            )
                        }
                    }
                }
            }
            if (group.extracting) {
                Spacer(Modifier.height(6.dp))
                ThinProgress(
                    if (group.extractPercent >= 0) group.extractPercent / 100f else null,
                    Modifier.padding(horizontal = 12.dp)
                )
            } else if (group.total > 0 && group.finished < group.items.size) {
                Spacer(Modifier.height(6.dp))
                ThinProgress(
                    group.done.toFloat() / group.total,
                    Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.downloads_delete_package_title),
            text = pluralStringResource(
                R.plurals.downloads_delete_package_text, group.items.size,
                group.pkg.name, group.items.size
            ),
            onConfirm = { vm.deletePackage(group.pkg.id) },
            onDismiss = { confirmDelete = false }
        )
    }

    if (renaming) {
        var name by rememberSaveable { mutableStateOf(group.pkg.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.downloads_rename_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { vm.renamePackage(group.pkg.id, name.trim()); renaming = false }
                ) { Text(stringResource(R.string.downloads_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    /** Live extraction percent, -1 = unknown. */
    extractPercent: Int,
    vm: DownloadViewModel,
    modifier: Modifier = Modifier
) {
    val hosterName = HosterRegistry.byId(item.hosterId)?.displayName ?: item.hosterId
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Free mode: live countdown of the wait time.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val freeWaiting = item.status == DownloadStatus.QUEUED && FreeMode.isWaitMessage(item.errorMessage)
    val captchaHold = item.status == DownloadStatus.QUEUED &&
        FreeMode.isCaptchaHold(item.errorMessage, item.retryAt, now)
    LaunchedEffect(freeWaiting, item.retryAt) {
        while (freeWaiting && item.retryAt > System.currentTimeMillis()) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.downloads_delete_item_title),
            text = stringResource(
                if (item.status == DownloadStatus.COMPLETED) R.string.downloads_delete_item_completed
                else R.string.downloads_delete_item_partial,
                item.fileName ?: item.url
            ),
            onConfirm = { vm.delete(item.id) },
            onDismiss = { confirmDelete = false }
        )
    }
    RowCard(modifier) {
        Column(Modifier.padding(start = 12.dp, end = 0.dp, top = 4.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.fileName ?: item.url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                val (pillRes, tone) = when (item.status) {
                    DownloadStatus.RUNNING -> R.string.downloads_status_running to Tone.ACTIVE
                    DownloadStatus.QUEUED -> R.string.downloads_status_queued to Tone.NEUTRAL
                    DownloadStatus.COLLECTED -> R.string.downloads_status_collected to Tone.NEUTRAL
                    DownloadStatus.PAUSED -> R.string.downloads_status_paused to Tone.WARNING
                    DownloadStatus.EXTRACTING -> R.string.downloads_status_extracting to Tone.ACTIVE
                    DownloadStatus.COMPLETED -> R.string.downloads_status_completed to Tone.SUCCESS
                    DownloadStatus.FAILED -> R.string.downloads_status_failed to Tone.ERROR
                    DownloadStatus.OFFLINE -> R.string.downloads_status_offline to Tone.ERROR
                }
                StatusPill(stringResource(pillRes), tone)
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.downloads_item_actions))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (captchaHold) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_menu_solve_captcha)) },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                onClick = { menuOpen = false; vm.solveCaptcha(item) }
                            )
                        }
                        when (item.status) {
                            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_pause)) },
                                leadingIcon = { Icon(JdIcons.Pause, contentDescription = null) },
                                onClick = { menuOpen = false; vm.pause(item.id) }
                            )
                            DownloadStatus.PAUSED -> DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_resume)) },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                onClick = { menuOpen = false; vm.resume(item) }
                            )
                            DownloadStatus.FAILED, DownloadStatus.OFFLINE -> DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_menu_retry)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = { menuOpen = false; vm.retry(item) }
                            )
                            DownloadStatus.COMPLETED -> {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_extract)) },
                                    leadingIcon = { Icon(JdIcons.Unarchive, contentDescription = null) },
                                    onClick = { menuOpen = false; vm.extract(item.id) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.downloads_menu_redownload)) },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = { menuOpen = false; vm.redownload(item) }
                                )
                            }
                            DownloadStatus.EXTRACTING, DownloadStatus.COLLECTED -> {}
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; confirmDelete = true }
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            val statusLine = when (item.status) {
                DownloadStatus.RUNNING -> {
                    val progress = stringResource(
                        R.string.downloads_bytes_of_total,
                        formatBytes(item.downloadedBytes), formatBytes(item.fileSize)
                    )
                    if (item.speedBps > 0) {
                        progress + SEPARATOR + stringResource(R.string.downloads_speed, formatBytes(item.speedBps))
                    } else progress
                }
                DownloadStatus.QUEUED -> when {
                    freeWaiting && item.retryAt > now -> {
                        val remaining = FreeMode.formatWait(FreeMode.remainingSeconds(item.retryAt, now))
                        val reason = FreeMode.waitReason(item.errorMessage)
                        if (reason == null) stringResource(R.string.downloads_free_wait, remaining)
                        else stringResource(R.string.downloads_free_wait_reason, remaining, reason)
                    }
                    else -> item.errorMessage?.let { noteText(it, item.retryAt, now) }
                        ?: stringResource(R.string.downloads_in_queue)
                }
                DownloadStatus.COLLECTED -> stringResource(R.string.downloads_not_started)
                DownloadStatus.PAUSED ->
                    stringResource(R.string.downloads_loaded_bytes, formatBytes(item.downloadedBytes))
                DownloadStatus.EXTRACTING ->
                    if (extractPercent >= 0) {
                        stringResource(R.string.downloads_extracting_archive_percent, extractPercent)
                    } else stringResource(R.string.downloads_extracting_archive)
                DownloadStatus.COMPLETED -> {
                    val path = item.localPath ?: ""
                    item.errorMessage?.let { stringResource(R.string.downloads_completed_note, path, noteText(it)) }
                        ?: path
                }
                DownloadStatus.FAILED -> item.errorMessage ?: stringResource(R.string.downloads_unknown_error)
                DownloadStatus.OFFLINE -> stringResource(R.string.downloads_file_offline)
            }
            MetaRow(
                stringResource(R.string.downloads_hoster_status, hosterName, statusLine),
                color = if (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.OFFLINE)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                hosterId = item.hosterId
            )
            if (item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.PAUSED) {
                Spacer(Modifier.height(6.dp))
                if (item.fileSize > 0) {
                    ThinProgress(item.downloadedBytes.toFloat() / item.fileSize, Modifier.padding(end = 8.dp))
                } else if (item.status == DownloadStatus.RUNNING) {
                    ThinProgress(null, Modifier.padding(end = 8.dp))
                }
            }
            if (item.status == DownloadStatus.EXTRACTING) {
                Spacer(Modifier.height(6.dp))
                ThinProgress(
                    if (extractPercent >= 0) extractPercent / 100f else null,
                    Modifier.padding(end = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
