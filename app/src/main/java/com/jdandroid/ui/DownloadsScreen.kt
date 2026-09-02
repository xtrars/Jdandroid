package com.jdandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jdandroid.container.ContainerFiles
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    vm: DownloadViewModel,
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
    dlcContent: String?,
    onDlcConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groups by vm.groups.collectAsState()
    val collapsed = remember { mutableStateMapOf<Long, Boolean>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf("") }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            prefill = sharedText
            showAddDialog = true
            onSharedTextConsumed()
        }
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // DLC-Datei direkt aus der App waehlen (System-Dateidialog);
    // DLC hat keinen registrierten MIME-Typ, daher alle Dateien anbieten
    val dlcPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            // Groessenbegrenzt lesen (siehe ContainerFiles): kein OutOfMemory
            // bei einer versehentlich gewaehlten grossen Datei.
            val result = runCatching { ContainerFiles.readText(context.contentResolver, uri) }
            val content = result.getOrNull()
            when {
                content == null -> launch(Dispatchers.Main) {
                    snackbarHost.showSnackbar(
                        result.exceptionOrNull()?.message ?: "DLC-Datei konnte nicht gelesen werden"
                    )
                }
                !ContainerFiles.looksLikeDlc(content) -> launch(Dispatchers.Main) {
                    snackbarHost.showSnackbar("Die gewählte Datei ist kein DLC-Container")
                }
                else -> vm.importDlc(content) { message ->
                    scope.launch { snackbarHost.showSnackbar(message) }
                }
            }
        }
    }

    LaunchedEffect(dlcContent) {
        if (dlcContent != null) {
            snackbarHost.showSnackbar("DLC wird importiert …")
            vm.importDlc(dlcContent) { result ->
                scope.launch { snackbarHost.showSnackbar(result) }
            }
            onDlcConsumed()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    IconButton(onClick = { dlcPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "DLC-Datei importieren")
                    }
                    TextButton(onClick = { vm.resumeAll() }) { Text("Alle starten") }
                    TextButton(onClick = { vm.pauseAll() }) { Text("Pause") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { prefill = ""; showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Links hinzufügen")
            }
        }
    ) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Noch keine Downloads.\nMit + Links einfügen, aus dem Browser teilen\n" +
                        "oder über das Ordner-Symbol eine DLC-Datei importieren.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
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
                            DownloadRow(item, vm, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddLinksDialog(
            initialText = prefill,
            onDismiss = { showAddDialog = false },
            onAdd = { text, pkg -> vm.addLinks(text, pkg) }
        )
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
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(12.dp)) {
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
                        fontWeight = FontWeight.SemiBold,
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
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR)
                    )
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
                            if (group.active) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (group.active) "Paket pausieren"
                            else "Paket starten"
                        )
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Paket löschen")
                    }
                }
            }
            if (group.total > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (group.done.toFloat() / group.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
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
        var name by remember { mutableStateOf(group.pkg.name) }
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
    var confirmDelete by remember { mutableStateOf(false) }
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
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                item.fileName ?: item.url,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            val statusLine = when (item.status) {
                DownloadStatus.RUNNING ->
                    "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.fileSize)}" +
                        if (item.speedBps > 0) " – ${formatBytes(item.speedBps)}/s" else ""
                DownloadStatus.QUEUED -> "Wartend"
                DownloadStatus.PAUSED -> "Pausiert (${formatBytes(item.downloadedBytes)})"
                DownloadStatus.EXTRACTING -> "Wird entpackt …"
                DownloadStatus.COMPLETED ->
                    "Fertig – ${item.localPath ?: ""}" +
                        (item.errorMessage?.let { " ($it)" } ?: "")
                DownloadStatus.FAILED -> "Fehler: ${item.errorMessage ?: "unbekannt"}"
                DownloadStatus.OFFLINE -> "Datei offline"
            }
            Text(
                "$hosterName · $statusLine",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.PAUSED) {
                Spacer(Modifier.height(6.dp))
                if (item.fileSize > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (item.downloadedBytes.toFloat() / item.fileSize).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (item.status == DownloadStatus.RUNNING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (item.status == DownloadStatus.EXTRACTING) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                        IconButton(onClick = { vm.pause(item.id) }) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause")
                        }
                    DownloadStatus.PAUSED ->
                        IconButton(onClick = { vm.resume(item) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Fortsetzen")
                        }
                    DownloadStatus.FAILED, DownloadStatus.OFFLINE ->
                        IconButton(onClick = { vm.retry(item) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Erneut versuchen")
                        }
                    DownloadStatus.COMPLETED, DownloadStatus.EXTRACTING -> {}
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen")
                }
            }
        }
    }
}
