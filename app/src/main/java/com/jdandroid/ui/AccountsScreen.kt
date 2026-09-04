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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    val message by vm.message.collectAsState()
    // Solange die Kontenansicht sichtbar ist (Tab offen, App im Vordergrund),
    // jede Minute den Stand beim Hoster nachladen; beim Oeffnen sofort.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshAll()
                delay(60_000)
            }
        }
    }
    LaunchedEffect(message) {
        message?.let {
            AppMessages.error(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Konten") }, colors = jdTopBarColors()) },
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
    var confirmDelete by remember { mutableStateOf(false) }
    val isPremium = account.valid && (
        account.premiumUntil > System.currentTimeMillis() ||
            (account.premiumUntil == 0L && account.statusText?.startsWith("Premium") == true)
    )

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
                    }
                    Text(details, style = MaterialTheme.typography.bodySmall, color = tint)
                }
                if (account.valid) {
                    Spacer(Modifier.height(6.dp))
                    TrafficLine(account)
                }
            }
            IconButton(onClick = { vm.check(account.id) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Prüfen")
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Löschen")
            }
        }
    }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Konto löschen?",
            text = "${hoster?.displayName ?: account.hosterId}: " +
                "${account.username ?: "API-Key"} wird entfernt. Downloads dieses Hosters " +
                "können danach nicht mehr starten.",
            onConfirm = { vm.delete(account) },
            onDismiss = { confirmDelete = false }
        )
    }
}

/** Verbleibende Restmenge des Kontos; "unbegrenzt" bei Hostern ohne Limit. */
@Composable
private fun TrafficLine(account: Account) {
    val left = account.trafficLeft
    val minutesAgo = ((System.currentTimeMillis() - account.lastChecked) / 60_000L).coerceAtLeast(0)
    val checked = when {
        account.lastChecked == 0L -> ""
        minutesAgo < 1 -> " · gerade geprüft"
        minutesAgo < 60 -> " · vor $minutesAgo min"
        else -> " · vor ${minutesAgo / 60} h"
    }
    val text = when {
        account.trafficUnlimited -> "unbegrenzt$checked"
        left >= 0 -> "${formatBytes(left)}$checked"
        else -> "Restmenge unbekannt$checked"
    }
    val low = left in 0 until (1L shl 30) && !account.trafficUnlimited
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    )
    // Balken: Restmenge im Verhaeltnis zum Kontingent (wenn der Hoster es meldet)
    val total = account.trafficTotal
    if (!account.trafficUnlimited && left >= 0 && total > 0) {
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (left.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun AddAccountDialog(vm: AccountViewModel, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<Hoster?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    val hoster = selected
    val valid = hoster != null && if (hoster.accountType == AccountType.USERNAME_PASSWORD) {
        username.isNotBlank() && password.isNotBlank()
    } else {
        apiKey.isNotBlank()
    }

    // Inhaltshoehe an die Bildschirmhoehe koppeln, damit Titel und Buttons
    // auch auf kleinen Displays mit offener Tastatur Platz behalten.
    val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp

    // Standard-AlertDialog: Groesse und Button-Platzierung uebernimmt das
    // Framework. Dadurch bleiben die Buttons immer im sichtbaren Bereich -
    // auch bei offener Tastatur und auf kleinen Displays. Der Inhalt scrollt.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konto hinzufügen", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Hoster wählen",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                vm.hosters.forEach { h ->
                    HosterSelectCard(
                        hoster = h,
                        selected = selected?.id == h.id,
                        onClick = { selected = h }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                hoster?.let { h ->
                    Spacer(Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text(
                            h.accountHint,
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (h.accountType == AccountType.USERNAME_PASSWORD) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Benutzername / E-Mail") },
                            leadingIcon = { Icon(Icons.Default.Person, null) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                autoCorrectEnabled = false
                            ),
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
                            // Passwort-Tastatur: keine Autokorrektur, kein Leerzeichen nach Punkt
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API-Key") },
                            leadingIcon = { Icon(Icons.Default.Key, null) },
                            // Wie die Browser-Adresszeile: keine Autokorrektur, keine Leerzeichen
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // Hoster mit CAPTCHA: Anmeldung im eingebetteten Browser
                    if (h.webLoginUrl != null) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { vm.requestWebLogin(h); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Person, null)
                            Spacer(Modifier.height(0.dp))
                            Text("  Im Browser anmelden (Benutzer/Passwort)")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    hoster?.let { vm.addAccount(it, username, password, apiKey) }
                    onDismiss()
                }
            ) { Text("Speichern & prüfen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
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
