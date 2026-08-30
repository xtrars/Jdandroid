package com.jdandroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jdandroid.data.PackageNaming
import com.jdandroid.hoster.LinkParser

@Composable
fun AddLinksDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onAdd: (String, String?) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var packageName by remember { mutableStateOf("") }
    val recognized = remember(text) { LinkParser.parse(text) }
    // Vorschlag aus den erkannten Links, wie im JDownloader
    val suggestion = remember(recognized) {
        if (recognized.isEmpty()) "" else PackageNaming.suggestFromUrls(recognized.map { it.first })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Links hinzufügen") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text("Links hier einfügen (einer pro Zeile oder beliebiger Text)") }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Paketname (optional)") },
                    placeholder = { Text(suggestion) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (recognized.isEmpty()) "Keine unterstützten Links erkannt " +
                        "(Rapidgator, 1fichier, ddownload)"
                    else "${recognized.size} Link(s) erkannt: " +
                        recognized.groupBy { it.second.displayName }
                            .entries.joinToString { "${it.value.size}× ${it.key}" },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = recognized.isNotEmpty(),
                onClick = {
                    onAdd(text, packageName.ifBlank { null })
                    onDismiss()
                }
            ) { Text("Hinzufügen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
