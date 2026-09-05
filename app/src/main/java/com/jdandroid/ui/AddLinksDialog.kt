package com.jdandroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jdandroid.R
import com.jdandroid.data.PackageNaming
import com.jdandroid.hoster.LinkParser

@Composable
fun AddLinksDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onAdd: (String, String?) -> Unit
) {
    // rememberSaveable: Eingaben ueberleben Drehen; der Schluessel initialText
    // setzt den Text bei neuer Vorbelegung (geteilter Text) zurueck.
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    var packageName by rememberSaveable { mutableStateOf("") }
    val recognized = remember(text) { LinkParser.parse(text) }
    // Vorschlag aus den erkannten Links, wie im JDownloader
    val suggestion = remember(recognized) {
        if (recognized.isEmpty()) "" else PackageNaming.suggestFromUrls(recognized.map { it.first })
    }

    // Inhaltshoehe an die Bildschirmhoehe koppeln: bei langen Linklisten und
    // offener Tastatur bleiben Paketname, Erkennungshinweis und die Knoepfe
    // sichtbar; der Inhalt scrollt, das Linkfeld scrollt zusaetzlich intern.
    val maxContentHeight = windowHeightDp() * 0.45f
    val resources = LocalResources.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.linkgrabber_add_title)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false
                    ),
                    placeholder = { Text(stringResource(R.string.linkgrabber_add_placeholder)) }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text(stringResource(R.string.linkgrabber_add_package_name)) },
                    placeholder = { Text(suggestion) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (recognized.isEmpty()) stringResource(R.string.linkgrabber_add_none_recognized)
                    else pluralStringResource(
                        R.plurals.linkgrabber_add_recognized, recognized.size, recognized.size,
                        recognized.groupBy { it.second.displayName }.entries.joinToString {
                            resources.getString(R.string.linkgrabber_add_hoster_count, it.value.size, it.key)
                        }
                    ),
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
            ) { Text(stringResource(R.string.linkgrabber_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
