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
    private val keyPasswords = stringPreferencesKey("archive_passwords")

    val maxConcurrent: Flow<Int> =
        context.dataStore.data.map { it[keyMaxConcurrent] ?: 2 }

    val exportToDownloads: Flow<Boolean> =
        context.dataStore.data.map { it[keyExportToDownloads] ?: true }

    val autoExtract: Flow<Boolean> =
        context.dataStore.data.map { it[keyAutoExtract] ?: true }

    val deleteArchiveAfterExtract: Flow<Boolean> =
        context.dataStore.data.map { it[keyDeleteArchive] ?: false }

    /** Passwortliste, ein Passwort pro Zeile. */
    val passwordList: Flow<String> =
        context.dataStore.data.map { it[keyPasswords] ?: "" }

    suspend fun currentMaxConcurrent(): Int = maxConcurrent.first()

    suspend fun currentExportToDownloads(): Boolean = exportToDownloads.first()

    suspend fun currentAutoExtract(): Boolean = autoExtract.first()

    suspend fun currentDeleteArchive(): Boolean = deleteArchiveAfterExtract.first()

    suspend fun currentPasswords(): List<String> =
        passwordList.first().lines().map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun setMaxConcurrent(value: Int) {
        context.dataStore.edit { it[keyMaxConcurrent] = value.coerceIn(1, 6) }
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
}
