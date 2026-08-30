package com.jdandroid.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jdandroid.data.Account
import com.jdandroid.hoster.AccountType
import com.jdandroid.hoster.HosterRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
        ) {
            items(accounts, key = { it.id }) { account ->
                AccountRow(account, vm)
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${hoster?.displayName ?: account.hosterId} – ${account.username ?: "API-Key"}",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            val details = buildString {
                append(account.statusText ?: "Noch nicht geprüft")
                if (account.premiumUntil > 0) {
                    append(" · bis ${dateFormat.format(Date(account.premiumUntil))}")
                }
                if (account.trafficLeft >= 0) {
                    append(" · ${formatBytes(account.trafficLeft)} Traffic")
                }
            }
            Text(details, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { vm.check(account.id) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Prüfen")
                }
                IconButton(onClick = { vm.delete(account) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen")
                }
            }
        }
    }
}

@Composable
private fun AddAccountDialog(vm: AccountViewModel, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(vm.hosters.first()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konto hinzufügen") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.hosters.forEach { hoster ->
                        FilterChip(
                            selected = selected.id == hoster.id,
                            onClick = { selected = hoster },
                            label = { Text(hoster.displayName) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(selected.accountHint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (selected.accountType == AccountType.USERNAME_PASSWORD) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Benutzername / E-Mail") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Passwort") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API-Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            val valid = if (selected.accountType == AccountType.USERNAME_PASSWORD) {
                username.isNotBlank() && password.isNotBlank()
            } else {
                apiKey.isNotBlank()
            }
            TextButton(
                enabled = valid,
                onClick = {
                    vm.addAccount(selected, username, password, apiKey)
                    onDismiss()
                }
            ) { Text("Speichern & prüfen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
