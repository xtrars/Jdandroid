package com.jdandroid.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

// Eine beschaedigte Einstellungsdatei (z.B. nach Stromausfall beim Schreiben)
// darf die App nicht bei jedem Start abstuerzen lassen: sie wird durch leere
// Voreinstellungen ersetzt.
private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class SettingsRepository(private val context: Context) {

    /**
     * Alle Flows leiten sich hiervon ab: ein Lesefehler liefert leere
     * Voreinstellungen statt den Sammler (und damit die Oberflaeche) zu beenden.
     */
    private val prefs: Flow<Preferences> = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private val keyMaxConcurrent = intPreferencesKey("max_concurrent")
    private val keyExportToDownloads = booleanPreferencesKey("export_to_downloads")
    private val keyAutoExtract = booleanPreferencesKey("auto_extract")
    private val keyDeleteArchive = booleanPreferencesKey("delete_archive_after_extract")
    private val keyFlatExtract = booleanPreferencesKey("flat_extract")
    private val keyRemoveAfterExtract = booleanPreferencesKey("remove_links_after_extract")
    private val keyPasswords = stringPreferencesKey("archive_passwords")
    private val keyExtractExcludes = stringPreferencesKey("extract_excludes")
    /** Alter Schluessel (KiB/s), nur noch zum Uebernehmen in Mbit/s. */
    private val keySpeedLimitKbps = intPreferencesKey("speed_limit_kbps")
    private val keySpeedLimitMbit = doublePreferencesKey("speed_limit_mbit")
    private val keyClickNLoad = booleanPreferencesKey("clicknload_enabled")
    private val keyWifiOnly = booleanPreferencesKey("wifi_only")
    private val keyAutoStart = booleanPreferencesKey("auto_start_links")
    private val keyFreeMode = booleanPreferencesKey("free_mode")
    private val keyDownloadTree = stringPreferencesKey("download_tree_uri")
    private val keyThemeMode = stringPreferencesKey("theme_mode")

    val maxConcurrent: Flow<Int> =
        prefs.map { it[keyMaxConcurrent] ?: 2 }

    val exportToDownloads: Flow<Boolean> =
        prefs.map { it[keyExportToDownloads] ?: true }

    val autoExtract: Flow<Boolean> =
        prefs.map { it[keyAutoExtract] ?: true }

    val deleteArchiveAfterExtract: Flow<Boolean> =
        prefs.map { it[keyDeleteArchive] ?: true }

    /** Flach entpacken: Ordner im Archiv ignorieren, alle Dateien direkt in den Paketordner. */
    val flatExtract: Flow<Boolean> =
        prefs.map { it[keyFlatExtract] ?: true }

    suspend fun currentFlatExtract(): Boolean = flatExtract.first()

    suspend fun setFlatExtract(value: Boolean) {
        context.dataStore.edit { it[keyFlatExtract] = value }
    }

    /** Eintraege eines Archivs nach erfolgreichem Entpacken aus der Liste entfernen (wie JDownloader). */
    val removeLinksAfterExtract: Flow<Boolean> =
        prefs.map { it[keyRemoveAfterExtract] ?: true }

    /** Passwortliste, ein Passwort pro Zeile. */
    val passwordList: Flow<String> =
        prefs.map { it[keyPasswords] ?: "" }

    /** Vom Entpacken ausgeschlossene Dateien (Muster mit * und ?), eines pro Zeile. */
    val extractExcludeList: Flow<String> =
        prefs.map { it[keyExtractExcludes] ?: "" }

    suspend fun currentExtractExcludes(): List<String> =
        extractExcludeList.first().lines().map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun addExtractExcludes(patterns: List<String>) {
        val fresh = patterns.map { it.trim() }.filter { it.isNotEmpty() }
        if (fresh.isEmpty()) return
        context.dataStore.edit { prefs ->
            val existing = (prefs[keyExtractExcludes] ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
            prefs[keyExtractExcludes] = (existing + fresh).distinct().joinToString("\n")
        }
    }

    suspend fun removeExtractExclude(pattern: String) {
        context.dataStore.edit { prefs ->
            val remaining = (prefs[keyExtractExcludes] ?: "").lines()
                .map { it.trim() }.filter { it.isNotEmpty() && it != pattern }
            prefs[keyExtractExcludes] = remaining.joinToString("\n")
        }
    }

    /**
     * Globales Download-Limit in Mbit/s (1 Mbit = 1 000 000 Bit), 0 = unbegrenzt.
     * Ein alter Wert in KiB/s wird umgerechnet, bis der Nutzer neu speichert.
     */
    val speedLimitMbit: Flow<Double> =
        prefs.map { p ->
            p[keySpeedLimitMbit]
                ?: p[keySpeedLimitKbps]?.let { kbps -> kbps * 1024.0 * 8 / 1_000_000 }
                ?: 0.0
        }

    /** Downloads nur über nicht-getaktete Verbindungen (WLAN). */
    val wifiOnly: Flow<Boolean> =
        prefs.map { it[keyWifiOnly] ?: false }

    /**
     * Neue Links sofort starten statt sie im Linksammler zu sammeln.
     * Standard aus - wie im JDownloader landen Links erst im Linksammler.
     */
    val autoStartLinks: Flow<Boolean> =
        prefs.map { it[keyAutoStart] ?: false }

    /**
     * Free-Modus: Links ohne Konto laden (Wartezeiten, ggf. Captcha im
     * eingebetteten Browser). Standard an - wie im JDownloader.
     */
    val freeMode: Flow<Boolean> =
        prefs.map { it[keyFreeMode] ?: true }

    suspend fun currentFreeMode(): Boolean = freeMode.first()

    suspend fun setFreeMode(value: Boolean) {
        context.dataStore.edit { it[keyFreeMode] = value }
    }

    /** Per Storage Access Framework gewaehlter Zielordner (Tree-URI), null = Downloads/JDAndroid. */
    val downloadTreeUri: Flow<String?> =
        prefs.map { it[keyDownloadTree]?.ifBlank { null } }

    /** "system", "light" oder "dark". */
    val themeMode: Flow<String> = prefs.map { it[keyThemeMode] ?: "system" }

    suspend fun setThemeMode(value: String) {
        context.dataStore.edit { it[keyThemeMode] = value }
    }

    suspend fun currentAutoStartLinks(): Boolean = autoStartLinks.first()

    suspend fun setAutoStartLinks(value: Boolean) {
        context.dataStore.edit { it[keyAutoStart] = value }
    }

    suspend fun currentDownloadTreeUri(): String? = downloadTreeUri.first()

    suspend fun setDownloadTreeUri(value: String?) {
        context.dataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(keyDownloadTree) else prefs[keyDownloadTree] = value
        }
    }

    /** Ein Passwort aus der Liste entfernen. */
    suspend fun removePassword(password: String) {
        context.dataStore.edit { prefs ->
            val remaining = (prefs[keyPasswords] ?: "").lines()
                .map { it.trim() }.filter { it.isNotEmpty() && it != password }
            prefs[keyPasswords] = remaining.joinToString("\n")
        }
    }

    /** Click'n'Load-Server (Port 9666) aktiv. */
    val clickNLoadEnabled: Flow<Boolean> =
        prefs.map { it[keyClickNLoad] ?: false }

    suspend fun currentMaxConcurrent(): Int = maxConcurrent.first()

    suspend fun currentExportToDownloads(): Boolean = exportToDownloads.first()

    suspend fun currentAutoExtract(): Boolean = autoExtract.first()

    suspend fun currentDeleteArchive(): Boolean = deleteArchiveAfterExtract.first()

    suspend fun currentRemoveLinksAfterExtract(): Boolean = removeLinksAfterExtract.first()

    suspend fun setRemoveLinksAfterExtract(value: Boolean) {
        context.dataStore.edit { it[keyRemoveAfterExtract] = value }
    }

    suspend fun currentPasswords(): List<String> =
        passwordList.first().lines().map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun setMaxConcurrent(value: Int) {
        context.dataStore.edit { it[keyMaxConcurrent] = value.coerceIn(1, 99) }
    }

    suspend fun setSpeedLimitMbit(value: Double) {
        context.dataStore.edit {
            it[keySpeedLimitMbit] = value.coerceIn(0.0, 100_000.0)
            it.remove(keySpeedLimitKbps)
        }
    }

    companion object {
        /** Upper bound of the stored password list; every extraction tries all entries. */
        const val MAX_STORED_PASSWORDS = 200

        /** Bytes pro Sekunde fuer ein Limit in Mbit/s (0 = unbegrenzt). */
        fun mbitToBytesPerSecond(mbit: Double): Long =
            if (mbit <= 0) 0L else (mbit * 1_000_000 / 8).toLong()

        /** Appends [fresh] to [existing] without duplicates, dropping the oldest entries beyond the cap. */
        fun mergePasswords(existing: List<String>, fresh: List<String>): List<String> =
            (existing + fresh)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .takeLast(MAX_STORED_PASSWORDS)
    }

    suspend fun currentClickNLoadEnabled(): Boolean = clickNLoadEnabled.first()

    suspend fun currentWifiOnly(): Boolean = wifiOnly.first()

    suspend fun setWifiOnly(value: Boolean) {
        context.dataStore.edit { it[keyWifiOnly] = value }
    }

    suspend fun setClickNLoadEnabled(value: Boolean) {
        context.dataStore.edit { it[keyClickNLoad] = value }
    }

    suspend fun setExportToDownloads(value: Boolean) {
        context.dataStore.edit { it[keyExportToDownloads] = value }
    }

    suspend fun setAutoExtract(value: Boolean) {
        context.dataStore.edit { it[keyAutoExtract] = value }
    }

    suspend fun setDeleteArchiveAfterExtract(value: Boolean) {
        context.dataStore.edit { it[keyDeleteArchive] = value }
    }

    suspend fun setPasswordList(value: String) {
        context.dataStore.edit { it[keyPasswords] = value }
    }

    /** Neue Passwoerter (z.B. aus Click'n'Load) an die Liste anhaengen, ohne Duplikate. */
    suspend fun addPasswords(passwords: List<String>) {
        val fresh = passwords.map { it.trim() }.filter { it.isNotEmpty() }
        if (fresh.isEmpty()) return
        context.dataStore.edit { prefs ->
            val existing = (prefs[keyPasswords] ?: "").lines()
            prefs[keyPasswords] = mergePasswords(existing, fresh).joinToString("\n")
        }
    }
}
