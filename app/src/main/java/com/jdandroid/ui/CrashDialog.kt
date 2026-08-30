package com.jdandroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jdandroid.CrashReporter

/**
 * Zeigt einen zuvor aufgezeichneten Absturz direkt beim Start an. In den
 * Einstellungen vergraben wuerde er leicht uebersehen - fuer die Fehlersuche
 * ist genau dieser Text aber entscheidend.
 */
@Composable
fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Die App ist abgestürzt") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Bitte diesen Bericht kopieren und weitergeben – daraus lässt " +
                        "sich die Ursache eindeutig bestimmen.",
                    style = MaterialTheme.typography.bodyMedium
                )
                SelectionContainer {
                    Text(
                        report.take(4000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Absturzbericht", report))
            }) { Text("Kopieren") }
        },
        dismissButton = {
            TextButton(onClick = {
                CrashReporter.clear(context)
                onDismiss()
            }) { Text("Verwerfen") }
        }
    )
}
