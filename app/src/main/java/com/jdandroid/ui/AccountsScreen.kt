package com.jdandroid.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jdandroid.data.Account
import com.jdandroid.hoster.AccountType
import com.jdandroid.hoster.Hoster
import com.jdandroid.hoster.HosterRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Markenfarbe je Hoster für Avatare/Akzente. */
private fun hosterColor(id: String): Color = when (id) {
    "rapidgator" -> Color(0xFF2E7D32)
    "onefichier" -> Color(0xFF1565C0)
    "ddownload" -> Color(0xFF6A1B9A)
    else -> Color(0xFF455A64)
}

@Composable
private fun HosterAvatar(hoster: Hoster, size: Int = 44) {
    Box(
        Modifier
            .size(size.dp)
            .background(hosterColor(hoster.id), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            hoster.displayName.first().uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(vm: AccountViewModel, modifier: Modifier = Modifier) {
    val accounts by vm.accounts.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Konten") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Konto hinzufügen")
            }
        }
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Noch keine Konten.\n\nMit + einen Premium-Account oder API-Key " +
                        "hinterlegen, damit Downloads starten können.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
            ) {
                items(accounts, key = { it.id }) { account -> AccountRow(account, vm) }
            }
        }
    }

    if (showAdd) {
        AddAccountDialog(vm, onDismiss = { showAdd = false })
    }
}

@Composable
private fun AccountRow(account: Account, vm: AccountViewModel) {
    val hoster = HosterRegistry.byId(account.hosterId)
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY) }
    val isPremium = account.valid && account.premiumUntil > System.currentTimeMillis()

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            hoster?.let { HosterAvatar(it) }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hoster?.displayName ?: account.hosterId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    account.username ?: "API-Key hinterlegt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        isPremium -> Icons.Default.CheckCircle to Color(0xFF2E7D32)
                        account.valid -> Icons.Default.Error to Color(0xFFEF6C00)
                        account.lastChecked > 0 -> Icons.Default.Error to MaterialTheme.colorScheme.error
                        else -> Icons.Default.Refresh to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    val details = buildString {
                        append(account.statusText ?: "Noch nicht geprüft")
                        if (isPremium && account.premiumUntil > 0) {
                            append(" · bis ${dateFormat.format(Date(account.premiumUntil))}")
                        }
                        if (account.trafficLeft >= 0) append(" · ${formatBytes(account.trafficLeft)}")
                    }
                    Text(details, style = MaterialTheme.typography.bodySmall, color = tint)
                }
            }
            IconButton(onClick = { vm.check(account.id) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Prüfen")
            }
            IconButton(onClick = { vm.delete(account) }) {
                Icon(Icons.Default.Delete, contentDescription = "Löschen")
            }
        }
    }
}

@Composable
private fun AddAccountDialog(vm: AccountViewModel, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<Hoster?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                // Kopfzeile
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Konto hinzufügen",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            "Hoster wählen",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(vm.hosters, key = { it.id }) { hoster ->
                        HosterSelectCard(
                            hoster = hoster,
                            selected = selected?.id == hoster.id,
                            onClick = { selected = hoster }
                        )
                    }

                    selected?.let { hoster ->
                        item {
                            Spacer(Modifier.height(4.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Text(
                                    hoster.accountHint,
                                    Modifier.padding(14.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        item {
                            if (hoster.accountType == AccountType.USERNAME_PASSWORD) {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text("Benutzername / E-Mail") },
                                    leadingIcon = { Icon(Icons.Default.Person, null) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Passwort") },
                                    leadingIcon = { Icon(Icons.Default.Key, null) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = { apiKey = it },
                                    label = { Text("API-Key") },
                                    leadingIcon = { Icon(Icons.Default.Key, null) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Aktionsleiste
                val hoster = selected
                val valid = hoster != null && if (hoster.accountType == AccountType.USERNAME_PASSWORD) {
                    username.isNotBlank() && password.isNotBlank()
                } else {
                    apiKey.isNotBlank()
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Abbrechen")
                    }
                    androidx.compose.material3.Button(
                        enabled = valid,
                        onClick = {
                            hoster?.let { vm.addAccount(it, username, password, apiKey) }
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Speichern & prüfen") }
                }
            }
        }
    }
}

@Composable
private fun HosterSelectCard(hoster: Hoster, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(
        if (selected) hosterColor(hoster.id) else MaterialTheme.colorScheme.outlineVariant,
        label = "border"
    )
    Card(
        onClick = onClick,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            HosterAvatar(hoster)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hoster.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when (hoster.accountType) {
                        AccountType.USERNAME_PASSWORD -> "Login mit Benutzername & Passwort"
                        AccountType.API_KEY -> "Login mit API-Key"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Ausgewählt",
                    tint = hosterColor(hoster.id)
                )
            }
        }
    }
}
