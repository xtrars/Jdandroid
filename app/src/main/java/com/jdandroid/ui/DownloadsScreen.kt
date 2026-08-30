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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus
import com.jdandroid.hoster.HosterRegistry
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = listOf("B", "KB", "MB", "GB", "TB")
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
    val downloads by vm.downloads.collectAsState()
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
            val content = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (content == null) {
                launch(Dispatchers.Main) {
                    snackbarHost.showSnackbar("DLC-Datei konnte nicht gelesen werden")
                }
            } else {
                vm.importDlc(content) { result ->
                    scope.launch { snackbarHost.showSnackbar(result) }
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
        if (downloads.isEmpty()) {
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
                items(downloads, key = { it.id }) { item ->
                    DownloadRow(item, vm)
                }
            }
        }
    }

    if (showAddDialog) {
        AddLinksDialog(
            initialText = prefill,
            onDismiss = { showAddDialog = false },
            onAdd = { text -> vm.addLinks(text) }
        )
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, vm: DownloadViewModel) {
    val hosterName = HosterRegistry.byId(item.hosterId)?.displayName ?: item.hosterId
    Card(Modifier.fillMaxWidth()) {
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
                style = MaterialTheme.typography.bodySmall,
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
                IconButton(onClick = { vm.delete(item.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen")
                }
            }
        }
    }
}
