package com.jdandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Gemeinsame Bausteine, damit alle Bildschirme gleich aussehen: Zustandsplakette,
 * Paket-/Zeilenkarten, Titelleistenfarben, duenner Fortschrittsbalken.
 */

/** Farbliche Bedeutung einer Plakette; getrennt von der Akzentfarbe. */
enum class Tone { NEUTRAL, ACTIVE, SUCCESS, WARNING, ERROR }

@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        Tone.ACTIVE -> scheme.primaryContainer to scheme.onPrimaryContainer
        Tone.SUCCESS -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Tone.WARNING -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Tone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1
    )
}

/** Kopfkarte eines Pakets: deutlich abgesetzt von den Zeilen darunter. */
@Composable
fun PackageCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) { Column { content() } }
}

/** Zeile innerhalb eines Pakets: flacher, leicht abgesetzter Hintergrund. */
@Composable
fun RowCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) { Column { content() } }
}

/** Duenner, abgerundeter Fortschrittsbalken; null = unbestimmt. */
@Composable
fun ThinProgress(fraction: Float?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val m = modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
    if (fraction == null) LinearProgressIndicator(modifier = m, color = color)
    else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = m, color = color)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun jdTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
    titleContentColor = MaterialTheme.colorScheme.onSurface
)

/** Abschnittsueberschrift in Einstellungen. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(top = 8.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

/** Gruppe von Einstellungen in einer Karte. */
@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) { Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) { content() } }
}

/** Kleine Zeile "Bezeichnung · Wert" fuer Meta-Angaben. */
@Composable
fun MetaRow(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = color
        )
    }
}
