package com.jdandroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jdandroid.R
import com.jdandroid.core.MessageKind

/**
 * Snackbar visuals for [com.jdandroid.core.AppMessages], shown above the
 * navigation bar regardless of the current tab.
 */
data class JdMessage(
    override val message: String,
    val kind: MessageKind = MessageKind.INFO,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration = when (kind) {
        MessageKind.PROGRESS -> SnackbarDuration.Indefinite
        MessageKind.ERROR -> SnackbarDuration.Long
        else -> SnackbarDuration.Short
    },
    override val withDismissAction: Boolean = kind == MessageKind.ERROR
) : SnackbarVisuals

/** Snackbar host styled with the theme's surface colors and an icon per kind. */
@Composable
fun JdSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = state, modifier = modifier) { data ->
        JdSnackbar(data, (data.visuals as? JdMessage)?.kind ?: MessageKind.INFO)
    }
}

@Composable
private fun JdSnackbar(data: SnackbarData, kind: MessageKind) {
    val scheme = MaterialTheme.colorScheme
    val accent = when (kind) {
        MessageKind.INFO -> scheme.primary
        MessageKind.PROGRESS -> scheme.primary
        MessageKind.SUCCESS -> scheme.tertiary
        MessageKind.ERROR -> scheme.error
    }
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (kind) {
                MessageKind.PROGRESS -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = accent
                )
                MessageKind.SUCCESS -> Icon(
                    Icons.Default.CheckCircle, contentDescription = null,
                    tint = accent, modifier = Modifier.size(22.dp)
                )
                MessageKind.ERROR -> Icon(
                    JdIcons.Error, contentDescription = null,
                    tint = accent, modifier = Modifier.size(22.dp)
                )
                MessageKind.INFO -> Icon(
                    Icons.Default.Info, contentDescription = null,
                    tint = accent, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }) { Text(label) }
            }
            if (data.visuals.withDismissAction) {
                IconButton(onClick = { data.dismiss() }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
