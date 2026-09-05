package com.jdandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jdandroid.R
import com.jdandroid.core.formatBytes
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.OnlineState
import com.jdandroid.hoster.HosterRegistry

/**
 * Linksammler wie im JDownloader: neue Links landen hier, werden online
 * geprueft (Name, Groesse, verfuegbar?) und erst auf "Starten" in die
 * Download-Liste uebernommen. Der Dialog "Links hinzufuegen" sowie der
 * DLC-Import per "Oeffnen mit" laufen tab-unabhaengig in der MainActivity;
 * hier bleibt der DLC-Dateiwaehler in der Titelleiste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkGrabberScreen(
    vm: DownloadViewModel,
    modifier: Modifier = Modifier
) {
    val groups by vm.collectorGroups.collectAsStateWithLifecycle()
    // Zugeklappte Pakete ueberleben Drehen und Tabwechsel (nur die IDs gesichert)
    val collapsed = rememberSaveable(saver = collectorCollapsedSaver) { mutableStateMapOf<Long, Boolean>() }
    val total = groups.sumOf { it.items.size }
    val offline = groups.sumOf { g -> g.items.count { it.online == OnlineState.OFFLINE } }
    val context = LocalContext.current

    // DLC aus der App heraus waehlen (Systemdialog; DLC hat keinen MIME-Typ).
    // Lesen und Import laufen im ViewModel, damit Drehen oder ein Tabwechsel
    // waehrend des Lesens nichts abbricht.
    val dlcPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importDlcFromUri(context.contentResolver, uri)
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linkgrabber_title)) },
                colors = jdTopBarColors(),
                actions = {
                    IconButton(onClick = { dlcPicker.launch(arrayOf("*/*")) }) {
                        Icon(JdIcons.UploadFile, contentDescription = stringResource(R.string.linkgrabber_import_dlc))
                    }
                    IconButton(onClick = { vm.recheckCollected() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.linkgrabber_recheck))
                    }
                    TextButton(enabled = total > 0, onClick = { vm.startAllCollected() }) {
                        Text(stringResource(R.string.linkgrabber_start_all))
                    }
                }
            )
        }
    ) { padding ->
        // Seitliche Insets (Displayausschnitt, Querformat) freihalten
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        if (groups.isEmpty()) {
            Box(content.padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.linkgrabber_empty_hint),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                content,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                // Unten Platz fuer den Plus-Knopf, damit er die letzte Zeile nicht verdeckt
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp)
            ) {
                if (offline > 0) {
                    item(key = "offline-hint") {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPill(pluralStringResource(R.plurals.linkgrabber_offline_count, offline, offline), Tone.ERROR)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.removeOfflineCollected() }) {
                                Text(stringResource(R.string.linkgrabber_remove_offline))
                            }
                        }
                    }
                }
                groups.forEach { group ->
                    val isCollapsed = collapsed[group.pkg.id] ?: false
                    item(key = "cpkg-${group.pkg.id}") {
                        CollectorPackageHeader(
                            group, vm,
                            collapsed = isCollapsed,
                            onToggle = { collapsed[group.pkg.id] = !isCollapsed }
                        )
                    }
                    if (!isCollapsed) {
                        items(group.items, key = { "c-${it.id}" }) { item ->
                            CollectorRow(item, vm, Modifier.padding(start = 10.dp))
                        }
                    }
                    item(key = "cgap-${group.pkg.id}") { Spacer(Modifier.height(6.dp)) }
                }
            }
        }
    }
}

/** Zugeklappte Pakete ueber Drehen/Tabwechsel behalten: nur die IDs sichern. */
private val collectorCollapsedSaver = listSaver<SnapshotStateMap<Long, Boolean>, Long>(
    save = { map -> map.filterValues { it }.keys.toList() },
    restore = { ids -> mutableStateMapOf<Long, Boolean>().apply { ids.forEach { put(it, true) } } }
)

@Composable
private fun CollectorPackageHeader(
    group: DownloadGroup,
    vm: DownloadViewModel,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val online = group.items.count { it.online == OnlineState.ONLINE }
    val checking = group.items.count { it.online == OnlineState.CHECKING }
    val known = group.items.filter { it.fileSize > 0 }.sumOf { it.fileSize }

    PackageCard {
        Row(Modifier.padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(if (collapsed) R.string.linkgrabber_expand else R.string.linkgrabber_collapse)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    group.pkg.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // Vollstaendige Formatstrings je Baustein, mit " · " verbunden
                val parts = buildList {
                    add(pluralStringResource(R.plurals.linkgrabber_link_count, group.items.size, group.items.size))
                    add(pluralStringResource(R.plurals.linkgrabber_online_count, online, online))
                    if (checking > 0) add(pluralStringResource(R.plurals.linkgrabber_checking_count, checking, checking))
                    if (known > 0) add(formatBytes(known))
                    group.pkg.source?.let { add(stringResource(R.string.linkgrabber_source, it)) }
                }
                MetaRow(parts.joinToString(" · "))
            }
            if (group.pkg.id != 0L) {
                IconButton(onClick = { vm.startCollectedPackage(group.pkg.id) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.linkgrabber_start_package), tint = MaterialTheme.colorScheme.primary)
                }
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.linkgrabber_package_actions))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.linkgrabber_discard_package)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; confirmDelete = true }
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.linkgrabber_discard_package_question),
            text = pluralStringResource(
                R.plurals.linkgrabber_discard_package_text, group.items.size, group.pkg.name, group.items.size
            ),
            onConfirm = { vm.deletePackage(group.pkg.id) },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun CollectorRow(item: DownloadItem, vm: DownloadViewModel, modifier: Modifier = Modifier) {
    val hosterName = HosterRegistry.byId(item.hosterId)?.displayName ?: item.hosterId
    RowCard(modifier) {
        Row(Modifier.padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OnlineIcon(item.online)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.fileName ?: item.url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val stateText = stringResource(
                    when (item.online) {
                        OnlineState.ONLINE -> R.string.linkgrabber_state_online
                        OnlineState.OFFLINE -> R.string.linkgrabber_state_offline
                        OnlineState.CHECKING -> R.string.linkgrabber_state_checking
                        else -> R.string.linkgrabber_state_unchecked
                    }
                )
                MetaRow(
                    buildString {
                        append(hosterName)
                        if (item.fileSize > 0) append(" · ${formatBytes(item.fileSize)}")
                        append(" · ").append(stateText)
                        if (item.online != OnlineState.ONLINE) item.errorMessage?.let { append(" ($it)") }
                    },
                    color = if (item.online == OnlineState.OFFLINE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    hosterId = item.hosterId
                )
            }
            IconButton(onClick = { vm.delete(item.id) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.linkgrabber_remove_link))
            }
        }
    }
}

@Composable
private fun OnlineIcon(state: Int) {
    when (state) {
        OnlineState.ONLINE -> Icon(
            Icons.Default.CheckCircle, contentDescription = stringResource(R.string.linkgrabber_icon_online),
            tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp)
        )
        OnlineState.OFFLINE -> Icon(
            JdIcons.Error, contentDescription = stringResource(R.string.linkgrabber_icon_offline),
            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)
        )
        OnlineState.CHECKING -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp), strokeWidth = 2.dp
        )
        else -> Icon(
            JdIcons.Help, contentDescription = stringResource(R.string.linkgrabber_icon_unchecked),
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)
        )
    }
}
