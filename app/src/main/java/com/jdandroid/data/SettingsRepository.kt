package com.jdandroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val keyMaxConcurrent = intPreferencesKey("max_concurrent")
    private val keyExportToDownloads = booleanPreferencesKey("export_to_downloads")
    private val keyAutoExtract = booleanPreferencesKey("auto_extract")
    private val keyDeleteArchive = booleanPreferencesKey("delete_archive_after_extract")
    private val keyRemoveAfterExtract = booleanPreferencesKey("remove_links_after_extract")
    private val keyPasswords = stringPreferencesKey("archive_passwords")
    private val keySpeedLimit = intPreferencesKey("speed_limit_kbps")
    private val keyClickNLoad = booleanPreferencesKey("clicknload_enabled")
    private val keyWifiOnly = booleanPreferencesKey("wifi_only")
    private val keyAutoStart = booleanPreferencesKey("auto_start_links")
    private val keyDownloadTree = stringPreferencesKey("download_tree_uri")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyDynamicColors = booleanPreferencesKey("dynamic_colors")

    val maxConcurrent: Flow<Int> =
        context.dataStore.data.map { it[keyMaxConcurrent] ?: 2 }

    val exportToDownloads: Flow<Boolean> =
        context.dataStore.data.map { it[keyExportToDownloads] ?: true }

    val autoExtract: Flow<Boolean> =
        context.dataStore.data.map { it[keyAutoExtract] ?: true }

    val deleteArchiveAfterExtract: Flow<Boolean> =
        context.dataStore.data.map { it[keyDeleteArchive] ?: false }

    /** Eintraege eines Archivs nach erfolgreichem Entpacken aus der Liste entfernen (wie JDownloader). */
    val removeLinksAfterExtract: Flow<Boolean> =
        context.dataStore.data.map { it[keyRemoveAfterExtract] ?: true }

    /** Passwortliste, ein Passwort pro Zeile. */
    val passwordList: Flow<String> =
        context.dataStore.data.map { it[keyPasswords] ?: "" }

    /** Globales Download-Limit in KB/s, 0 = unbegrenzt. */
    val speedLimitKbps: Flow<Int> =
        context.dataStore.data.map { it[keySpeedLimit] ?: 0 }

    /** Downloads nur über nicht-getaktete Verbindungen (WLAN). */
    val wifiOnly: Flow<Boolean> =
        context.dataStore.data.map { it[keyWifiOnly] ?: false }

    /**
     * Neue Links sofort starten statt sie im Linksammler zu sammeln.
     * Standard aus - wie im JDownloader landen Links erst im Linksammler.
     */
    val autoStartLinks: Flow<Boolean> =
        context.dataStore.data.map { it[keyAutoStart] ?: false }

    /** Per Storage Access Framework gewaehlter Zielordner (Tree-URI), null = Downloads/JDAndroid. */
    val downloadTreeUri: Flow<String?> =
        context.dataStore.data.map { it[keyDownloadTree]?.ifBlank { null } }

    /** "system", "light" oder "dark". */
    val themeMode: Flow<String> = context.dataStore.data.map { it[keyThemeMode] ?: "system" }

    /** Material-You-Farben vom Hintergrundbild (ab Android 12); aus = eigene Palette. */
    val dynamicColors: Flow<Boolean> = context.dataStore.data.map { it[keyDynamicColors] ?: false }

    suspend fun setThemeMode(value: String) {
        context.dataStore.edit { it[keyThemeMode] = value }
    }

    suspend fun setDynamicColors(value: Boolean) {
        context.dataStore.edit { it[keyDynamicColors] = value }
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
        context.dataStore.data.map { it[keyClickNLoad] ?: false }

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

    suspend fun setSpeedLimitKbps(value: Int) {
        context.dataStore.edit { it[keySpeedLimit] = value.coerceIn(0, 1_000_000) }
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
            val existing = (prefs[keyPasswords] ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
            val merged = (existing + fresh).distinct()
            prefs[keyPasswords] = merged.joinToString("\n")
        }
    }
}
