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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                title = { Text("Linksammler") },
                colors = jdTopBarColors(),
                actions = {
                    IconButton(onClick = { dlcPicker.launch(arrayOf("*/*")) }) {
                        Icon(JdIcons.FolderOpen, contentDescription = "DLC-Datei importieren")
                    }
                    IconButton(onClick = { vm.recheckCollected() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Erneut prüfen")
                    }
                    TextButton(enabled = total > 0, onClick = { vm.startAllCollected() }) {
                        Text("Alle starten")
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
                    "Der Linksammler ist leer.\n\nNeue Links (Einfügen, Teilen, DLC über das " +
                        "Ordner-Symbol, Click'n'Load) erscheinen hier, werden online geprüft " +
                        "und starten erst auf Wunsch.",
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
                            StatusPill("$offline offline", Tone.ERROR)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.removeOfflineCollected() }) {
                                Text("Offline-Links entfernen")
                            }
                        }
                    }
                }
                groups.forEach { group ->
                    item(key = "cpkg-${group.pkg.id}") { CollectorPackageHeader(group, vm) }
                    items(group.items, key = { "c-${it.id}" }) { item ->
                        CollectorRow(item, vm, Modifier.padding(start = 10.dp))
                    }
                    item(key = "cgap-${group.pkg.id}") { Spacer(Modifier.height(6.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CollectorPackageHeader(group: DownloadGroup, vm: DownloadViewModel) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val online = group.items.count { it.online == OnlineState.ONLINE }
    val checking = group.items.count { it.online == OnlineState.CHECKING }
    val known = group.items.filter { it.fileSize > 0 }.sumOf { it.fileSize }

    PackageCard {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    group.pkg.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                MetaRow(
                    buildString {
                        append("${group.items.size} Link(s) · $online online")
                        if (checking > 0) append(" · $checking in Prüfung")
                        if (known > 0) append(" · ${formatBytes(known)}")
                        group.pkg.source?.let { append(" · von $it") }
                    }
                )
            }
            if (group.pkg.id != 0L) {
                IconButton(onClick = { vm.startCollectedPackage(group.pkg.id) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Paket starten", tint = MaterialTheme.colorScheme.primary)
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
                MetaRow(
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
                    color = if (item.online == OnlineState.OFFLINE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
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
            tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp)
        )
        OnlineState.OFFLINE -> Icon(
            JdIcons.Error, contentDescription = "Offline",
            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)
        )
        OnlineState.CHECKING -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp), strokeWidth = 2.dp
        )
        else -> Icon(
            JdIcons.Help, contentDescription = "Nicht geprüft",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)
        )
    }
}
