package com.jdandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.OnlineState
import com.jdandroid.hoster.HosterRegistry

/**
 * Linksammler wie im JDownloader: neue Links landen hier, werden online
 * geprueft (Name, Groesse, verfuegbar?) und erst auf "Starten" in die
 * Download-Liste uebernommen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkGrabberScreen(vm: DownloadViewModel, modifier: Modifier = Modifier) {
    val groups by vm.collectorGroups.collectAsState()
    val total = groups.sumOf { it.items.size }
    val offline = groups.sumOf { g -> g.items.count { it.online == OnlineState.OFFLINE } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Linksammler") },
                actions = {
                    IconButton(onClick = { vm.recheckCollected() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Erneut prüfen")
                    }
                    TextButton(
                        enabled = total > 0,
                        onClick = { vm.startAllCollected() }
                    ) { Text("Alle starten") }
                }
            )
        }
    ) { padding ->
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Der Linksammler ist leer.\n\nNeue Links (Einfügen, Teilen, DLC, " +
                        "Click'n'Load) erscheinen hier, werden online geprüft und " +
                        "starten erst auf Wunsch.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                if (offline > 0) {
                    item(key = "offline-hint") {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "$offline Link(s) offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.removeOfflineCollected() }) {
                                Text("Offline-Links entfernen")
                            }
                        }
                    }
                }
                groups.forEach { group ->
                    item(key = "cpkg-${group.pkg.id}") {
                        CollectorPackageHeader(group, vm)
                    }
                    items(group.items, key = { "c-${it.id}" }) { item ->
                        CollectorRow(item, vm, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectorPackageHeader(group: DownloadGroup, vm: DownloadViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    val online = group.items.count { it.online == OnlineState.ONLINE }
    val checking = group.items.count { it.online == OnlineState.CHECKING }
    val known = group.items.filter { it.fileSize > 0 }.sumOf { it.fileSize }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    group.pkg.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append("${group.items.size} Link(s) · $online online")
                        if (checking > 0) append(" · $checking in Prüfung")
                        if (known > 0) append(" · ${formatBytes(known)}")
                        group.pkg.source?.let { append(" · von $it") }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (group.pkg.id != 0L) {
                IconButton(onClick = { vm.startCollectedPackage(group.pkg.id) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Paket starten")
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Paket verwerfen")
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Paket verwerfen?",
            text = "\"${group.pkg.name}\" mit ${group.items.size} Link(s) wird aus dem Linksammler entfernt.",
            onConfirm = { vm.deletePackage(group.pkg.id) },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun CollectorRow(item: DownloadItem, vm: DownloadViewModel, modifier: Modifier = Modifier) {
    val hosterName = HosterRegistry.byId(item.hosterId)?.displayName ?: item.hosterId
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OnlineIcon(item.online)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.fileName ?: item.url,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(hosterName)
                        if (item.fileSize > 0) append(" · ${formatBytes(item.fileSize)}")
                        when (item.online) {
                            OnlineState.ONLINE -> append(" · online")
                            OnlineState.OFFLINE -> append(" · offline")
                            OnlineState.CHECKING -> append(" · wird geprüft …")
                            else -> append(" · nicht geprüft")
                        }
                        if (item.online != OnlineState.ONLINE) item.errorMessage?.let { append(" ($it)") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { vm.delete(item.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Link entfernen")
            }
        }
    }
}

@Composable
private fun OnlineIcon(state: Int) {
    when (state) {
        OnlineState.ONLINE -> Icon(
            Icons.Default.CheckCircle, contentDescription = "Online",
            tint = Color(0xFF2E7D32), modifier = Modifier.size(22.dp)
        )
        OnlineState.OFFLINE -> Icon(
            Icons.Default.Error, contentDescription = "Offline",
            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)
        )
        OnlineState.CHECKING -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp), strokeWidth = 2.dp
        )
        else -> Icon(
            Icons.Default.Help, contentDescription = "Nicht geprüft",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)
        )
    }
}
