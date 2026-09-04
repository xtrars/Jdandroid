package com.jdandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
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
fun MetaRow(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    hosterId: String? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Kleines Hoster-Symbol vor dem Text, falls eines hinterlegt ist
        hosterId?.let { hosterIconRes(it) }?.let { res ->
            Image(
                painterResource(res),
                contentDescription = null,
                modifier = Modifier.size(14.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = color
        )
    }
}

/** Symbol je Hoster (siehe THIRD_PARTY_NOTICES.md); null, wenn keines hinterlegt ist. */
fun hosterIconRes(id: String): Int? = when (id) {
    "rapidgator" -> com.jdandroid.R.drawable.hoster_rapidgator
    "onefichier" -> com.jdandroid.R.drawable.hoster_onefichier
    "ddownload" -> com.jdandroid.R.drawable.hoster_ddownload
    else -> null
}

/**
 * Symbole, die nicht im schlanken material-icons-core enthalten sind. Die
 * Pfaddaten entsprechen den Material-Symbolen (24-dp-Raster), damit das
 * Erscheinungsbild dem der uebrigen Icons entspricht - ohne das grosse
 * icons-extended-Paket (mehrere MB) einzubinden.
 */
object JdIcons {
    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

    /** Archiv mit Pfeil nach oben: Entpacken. */
    val Unarchive: ImageVector by lazy {
        icon(
            "Unarchive",
            "M20.55,5.22l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.15,0.55L3.46,5.22" +
                "C3.17,5.57 3,6.01 3,6.5V19c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5c0,-0.49 -0.17,-0.93 " +
                "-0.45,-1.28zM12,9.5l5.5,5.5H14v2h-4v-2H6.5L12,9.5zM5.12,5l0.81,-1h12l0.94,1H5.12z"
        )
    }

    /** Datei mit Pfeil nach oben: Datei importieren (DLC). */
    val UploadFile: ImageVector by lazy {
        icon(
            "UploadFile",
            "M14,2H6C4.9,2 4.01,2.9 4.01,4L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8L14,2zM18,20H6V4" +
                "h7v5h5V20zM8,15.01l1.41,1.41L11,14.84V19h2v-4.16l1.59,1.59L16,15.01L12.01,11L8,15.01z"
        )
    }

    /** Pfeil nach unten auf einer Linie: Downloads. */
    val Download: ImageVector by lazy {
        icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    }

    /** Kettenglied: Linksammler. */
    val Link: ImageVector by lazy {
        icon(
            "Link",
            "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7" +
                "c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1" +
                "s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z"
        )
    }

    /** Zwei Balken: Pause. */
    val Pause: ImageVector by lazy {
        icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    }

    /** Geoeffneter Ordner: Datei waehlen. */
    val FolderOpen: ImageVector by lazy {
        icon(
            "FolderOpen",
            "M20,6h-8l-2,-2H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8" +
                "c0,-1.1 -0.9,-2 -2,-2zM20,18H4V8h16v10z"
        )
    }

    /** Kreis mit Ausrufezeichen: Fehler. */
    val Error: ImageVector by lazy {
        icon(
            "Error",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2z" +
                "M13,13h-2V7h2v6z"
        )
    }

    /** Kreis mit Fragezeichen: unbekannt / nicht geprueft. */
    val Help: ImageVector by lazy {
        icon(
            "Help",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,19h-2v-2h2v2z" +
                "M15.07,11.25l-0.9,0.92C13.45,12.9 13,13.5 13,15h-2v-0.5c0,-1.1 0.45,-2.1 1.17,-2.83" +
                "l1.24,-1.26c0.37,-0.36 0.59,-0.86 0.59,-1.41 0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2H8" +
                "c0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,0.88 -0.36,1.68 -0.93,2.25z"
        )
    }

    /** Schluessel: Passwort / API-Key. */
    val Key: ImageVector by lazy {
        icon(
            "Key",
            "M21,10h-8.35C11.83,7.67 9.61,6 7,6c-3.31,0 -6,2.69 -6,6s2.69,6 6,6c2.61,0 4.83,-1.67 " +
                "5.65,-4H13l2,2 2,-2 2,2 4,-4.04L21,10zM7,15c-1.65,0 -3,-1.35 -3,-3s1.35,-3 3,-3 " +
                "3,1.35 3,3 -1.35,3 -3,3z"
        )
    }
}
