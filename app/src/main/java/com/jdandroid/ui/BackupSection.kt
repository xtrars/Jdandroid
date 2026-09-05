package com.jdandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jdandroid.R
import com.jdandroid.data.BackupFormatException
import java.time.LocalDate

/** Collapsible backup section: credentials switch, save and restore, one result line. */
@Composable
internal fun BackupSection(vm: BackupViewModel = viewModel()) {
    val context = LocalContext.current
    val runner = vm.runner
    val busy by runner.busy.collectAsStateWithLifecycle()
    val outcome by runner.outcome.collectAsStateWithLifecycle()
    val pendingRestore by runner.pendingRestore.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var includeCredentials by rememberSaveable { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runner.save(context, uri, includeCredentials)
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runner.askRestore(uri)
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_backup_section), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_backup_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(if (expanded) R.string.settings_collapse else R.string.settings_expand)
        )
    }
    if (!expanded) return

    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = includeCredentials, role = Role.Switch, onValueChange = { includeCredentials = it })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_backup_include_credentials_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_backup_include_credentials_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = if (includeCredentials) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = includeCredentials, onCheckedChange = null)
    }
    val fileName = stringResource(R.string.settings_backup_file_name, LocalDate.now().toString())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { saver.launch(fileName) }, enabled = !busy) {
            Text(stringResource(R.string.settings_backup_save))
        }
        OutlinedButton(onClick = { opener.launch(arrayOf("application/json")) }, enabled = !busy) {
            Text(stringResource(R.string.settings_backup_restore))
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    outcome?.let { BackupOutcomeLine(it) }

    if (pendingRestore != null) {
        AlertDialog(
            onDismissRequest = { runner.cancelRestore() },
            title = { Text(stringResource(R.string.settings_backup_confirm_title)) },
            text = { Text(stringResource(R.string.settings_backup_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { runner.confirmRestore(context) }) {
                    Text(stringResource(R.string.settings_backup_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { runner.cancelRestore() }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun BackupOutcomeLine(outcome: BackupOutcome) {
    val text = when (outcome) {
        is BackupOutcome.Saved -> stringResource(
            if (outcome.withCredentials) R.string.settings_backup_saved_with_credentials else R.string.settings_backup_saved
        )
        is BackupOutcome.Restored -> stringResource(
            R.string.settings_backup_restored,
            pluralStringResource(R.plurals.settings_backup_restored_settings, outcome.result.settings, outcome.result.settings),
            pluralStringResource(R.plurals.settings_backup_restored_accounts, outcome.result.accounts, outcome.result.accounts)
        )
        is BackupOutcome.Failed -> when (val e = outcome.error) {
            is BackupFormatException -> when (e.reason) {
                BackupFormatException.Reason.INVALID -> stringResource(R.string.settings_backup_error_invalid)
                BackupFormatException.Reason.UNSUPPORTED_VERSION -> stringResource(R.string.settings_backup_error_version, e.version)
            }
            else -> stringResource(R.string.settings_backup_failed, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (outcome is BackupOutcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    )
}
