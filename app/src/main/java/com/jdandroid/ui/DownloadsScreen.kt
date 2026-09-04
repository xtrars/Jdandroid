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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.hoster.HosterRegistry
import java.util.Locale

/** Ziffern mit fester Breite, damit Zahlen beim Aktualisieren nicht springen. */
private const val TABULAR = "tnum"

/** Rueckfrage vor unwiderruflichem Loeschen. */
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
                Text("Löschen", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

/**
 * Binaere Einheiten mit korrekter Beschriftung (1 MiB = 1.048.576 Byte),
 * wie im JDownloader. Vorher stand "MB" an einem 1024er-Wert.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.GERMANY, "%.1f %s", value, units[unit])
}

/** Filter der Download-Liste (V5). */
private enum class ListFilter(val label: String, val matches: (DownloadItem) -> Boolean) {
    ALL("Alle", { true }),
    ACTIVE("Läuft", { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.EXTRACTING }),
    WAITING("Wartend", { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }),
    DONE("Fertig", { it.status == DownloadStatus.COMPLETED }),
    FAILED("Fehler", { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.OFFLINE })
}

/** Zugeklappte Pakete ueber Drehen/Tabwechsel behalten: nur die IDs sichern. */
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

    // Zurueck schliesst zuerst die Suche (vor dem Tabwechsel der MainActivity)
    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }
    // Suchfeld beim Oeffnen direkt fokussieren, damit die Tastatur erscheint
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }

    // Suche und Filter wirken auf Eintraege; Pakete ohne Treffer verschwinden
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
        // Untere Systemleiste behandelt bereits die NavigationBar der MainActivity
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                colors = jdTopBarColors(),
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                        Icon(
                            if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchOpen) "Suche schließen" else "Suchen"
                        )
                    }
                    TextButton(onClick = { vm.resumeAll() }) { Text("Alle starten") }
                    TextButton(onClick = { vm.pauseAll() }) { Text("Pause") }
                }
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Seitliche Insets (Displayausschnitt, Querformat) freihalten
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Dateiname oder Paket suchen") },
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
                    label = { Text(f.label) }
                )
            }
        }
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (allGroups.isEmpty()) {
                        "Noch keine Downloads.\n\nLinks werden im Linksammler hinzugefügt " +
                            "(Plus-Knopf, Teilen aus dem Browser, DLC, Click'n'Load) und " +
                            "von dort gestartet."
                    } else "Keine Einträge für diesen Filter.",
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
                            DownloadRow(item, vm, Modifier.padding(start = 10.dp))
                        }
                    }
                    item(key = "gap-${group.pkg.id}") { Spacer(Modifier.height(6.dp)) }
                }
            }
        }
        }
    }

}

/** Kopfzeile eines Pakets: Name, Gesamtfortschritt und Paketaktionen. */
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
                        contentDescription = if (collapsed) "Aufklappen" else "Zuklappen"
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        group.pkg.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val summary = buildString {
                        append("${group.items.size} Datei(en)")
                        append(" · ${group.finished} fertig")
                        if (group.failed > 0) append(" · ${group.failed} fehlerhaft")
                        if (group.total > 0) {
                            append(" · ${formatBytes(group.done)} / ${formatBytes(group.total)}")
                        }
                        if (group.speed > 0) append(" · ${formatBytes(group.speed)}/s")
                        group.pkg.source?.let { append(" · von $it") }
                    }
                    MetaRow(summary)
                }
                if (group.pkg.id != 0L) {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Paket umbenennen")
                    }
                    IconButton(onClick = {
                        if (group.active) vm.pausePackage(group.pkg.id)
                        else vm.startPackage(group.pkg.id)
                    }) {
                        Icon(
                            if (group.active) JdIcons.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (group.active) "Paket pausieren"
                            else "Paket starten"
                        )
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Paket löschen")
                    }
                }
            }
            if (group.total > 0 && group.finished < group.items.size) {
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
            title = "Paket löschen?",
            text = "\"${group.pkg.name}\" mit ${group.items.size} Download(s) wird entfernt. " +
                "Bereits geladene Teildateien werden gelöscht.",
            onConfirm = { vm.deletePackage(group.pkg.id) },
            onDismiss = { confirmDelete = false }
        )
    }

    if (renaming) {
        var name by rememberSaveable { mutableStateOf(group.pkg.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Paket umbenennen") },
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
                ) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    vm: DownloadViewModel,
    modifier: Modifier = Modifier
) {
    val hosterName = HosterRegistry.byId(item.hosterId)?.displayName ?: item.hosterId
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Download löschen?",
            text = (item.fileName ?: item.url) +
                if (item.status == DownloadStatus.COMPLETED) "\n\nDie fertige Datei bleibt erhalten."
                else "\n\nBereits geladene Daten gehen verloren.",
            onConfirm = { vm.delete(item.id) },
            onDismiss = { confirmDelete = false }
        )
    }
    RowCard(modifier) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.fileName ?: item.url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                val (pillText, tone) = when (item.status) {
                    DownloadStatus.RUNNING -> "Lädt" to Tone.ACTIVE
                    DownloadStatus.QUEUED -> "Wartend" to Tone.NEUTRAL
                    DownloadStatus.COLLECTED -> "Linksammler" to Tone.NEUTRAL
                    DownloadStatus.PAUSED -> "Pausiert" to Tone.WARNING
                    DownloadStatus.EXTRACTING -> "Entpackt" to Tone.ACTIVE
                    DownloadStatus.COMPLETED -> "Fertig" to Tone.SUCCESS
                    DownloadStatus.FAILED -> "Fehler" to Tone.ERROR
                    DownloadStatus.OFFLINE -> "Offline" to Tone.ERROR
                }
                StatusPill(pillText, tone, Modifier.padding(end = 8.dp))
            }
            Spacer(Modifier.height(3.dp))
            val statusLine = when (item.status) {
                DownloadStatus.RUNNING ->
                    "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.fileSize)}" +
                        if (item.speedBps > 0) " · ${formatBytes(item.speedBps)}/s" else ""
                DownloadStatus.QUEUED -> item.errorMessage ?: "in der Warteschlange"
                DownloadStatus.COLLECTED -> "noch nicht gestartet"
                DownloadStatus.PAUSED -> "${formatBytes(item.downloadedBytes)} geladen"
                DownloadStatus.EXTRACTING -> "Archiv wird entpackt …"
                DownloadStatus.COMPLETED ->
                    (item.localPath ?: "") + (item.errorMessage?.let { " ($it)" } ?: "")
                DownloadStatus.FAILED -> item.errorMessage ?: "unbekannter Fehler"
                DownloadStatus.OFFLINE -> "Datei beim Hoster nicht mehr vorhanden"
            }
            MetaRow(
                "$hosterName · $statusLine",
                color = if (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.OFFLINE)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
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
                ThinProgress(null, Modifier.padding(end = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                        IconButton(onClick = { vm.pause(item.id) }) {
                            Icon(JdIcons.Pause, contentDescription = "Pause")
                        }
                    DownloadStatus.PAUSED ->
                        IconButton(onClick = { vm.resume(item) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Fortsetzen")
                        }
                    DownloadStatus.FAILED, DownloadStatus.OFFLINE ->
                        IconButton(onClick = { vm.retry(item) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Erneut versuchen")
                        }
                    DownloadStatus.COMPLETED, DownloadStatus.EXTRACTING, DownloadStatus.COLLECTED -> {}
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen")
                }
            }
        }
    }
}
