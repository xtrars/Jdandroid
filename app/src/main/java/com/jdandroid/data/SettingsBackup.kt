package com.jdandroid.data

import android.content.Context
import com.jdandroid.JdApp
import com.jdandroid.hoster.HosterRegistry
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Settings as written to a backup file. A null field is absent from the file
 * and stays unchanged on restore; [count] is the number of present fields.
 */
data class SettingsSnapshot(
    val maxConcurrent: Int? = null,
    val speedLimitMbit: Double? = null,
    val wifiOnly: Boolean? = null,
    val autoStartLinks: Boolean? = null,
    val freeMode: Boolean? = null,
    val exportToDownloads: Boolean? = null,
    val autoExtract: Boolean? = null,
    val flatExtract: Boolean? = null,
    val deleteArchiveAfterExtract: Boolean? = null,
    val removeLinksAfterExtract: Boolean? = null,
    val passwordList: String? = null,
    val extractExcludeList: String? = null,
    val clickNLoadEnabled: Boolean? = null,
    val themeMode: String? = null,
    val downloadTreeUri: String? = null,
    val nfs: NfsSettings? = null
) {
    val count: Int
        get() = listOf(
            maxConcurrent, speedLimitMbit, wifiOnly, autoStartLinks, freeMode, exportToDownloads,
            autoExtract, flatExtract, deleteArchiveAfterExtract, removeLinksAfterExtract, passwordList,
            extractExcludeList, clickNLoadEnabled, themeMode, downloadTreeUri, nfs
        ).count { it != null }
}

/** Credentials of one account in plain text. */
data class AccountBackup(
    val hosterId: String,
    val username: String? = null,
    val password: String? = null,
    val apiKey: String? = null,
    val cookies: String? = null
) {
    val hasCredentials: Boolean get() = !password.isNullOrEmpty() || !apiKey.isNullOrEmpty() || !cookies.isNullOrEmpty()
}

/** Content of a backup file; [accounts] is null when credentials were not included. */
data class SettingsBackup(
    val createdAt: Long,
    val appVersion: String,
    val settings: SettingsSnapshot,
    val accounts: List<AccountBackup>? = null
)

/** Outcome of a restore, reduced to what the result line shows. */
data class RestoreResult(val settings: Int, val accounts: Int)

class BackupFormatException(val reason: Reason, val version: Int = 0) : Exception(reason.name) {
    enum class Reason { INVALID, UNSUPPORTED_VERSION }
}

/**
 * JSON form of a [SettingsBackup]: a marker, the format version and the
 * settings under their DataStore key names. Reading tolerates missing keys
 * so older or hand-edited files restore what they contain.
 */
object SettingsBackupFormat {
    const val VERSION = 1
    private const val MARKER = "jdandroid-backup"

    fun write(backup: SettingsBackup): String {
        val s = backup.settings
        val settings = JSONObject().apply {
            put("max_concurrent", s.maxConcurrent)
            put("speed_limit_mbit", s.speedLimitMbit)
            put("wifi_only", s.wifiOnly)
            put("auto_start_links", s.autoStartLinks)
            put("free_mode", s.freeMode)
            put("export_to_downloads", s.exportToDownloads)
            put("auto_extract", s.autoExtract)
            put("flat_extract", s.flatExtract)
            put("delete_archive_after_extract", s.deleteArchiveAfterExtract)
            put("remove_links_after_extract", s.removeLinksAfterExtract)
            put("archive_passwords", s.passwordList)
            put("extract_excludes", s.extractExcludeList)
            put("clicknload_enabled", s.clickNLoadEnabled)
            put("theme_mode", s.themeMode)
            put("download_tree_uri", s.downloadTreeUri)
            s.nfs?.let { n ->
                put("nfs", JSONObject().apply {
                    put("enabled", n.enabled)
                    put("server", n.server)
                    put("export", n.export)
                    put("uid", n.uid)
                    put("gid", n.gid)
                    put("subdir", n.subDir)
                })
            }
        }
        val root = JSONObject().apply {
            put("format", MARKER)
            put("version", VERSION)
            put("created_at", backup.createdAt)
            put("app_version", backup.appVersion)
            put("settings", settings)
            backup.accounts?.let { list ->
                put("accounts", JSONArray().apply {
                    list.forEach { a ->
                        put(JSONObject().apply {
                            put("hoster", a.hosterId)
                            put("username", a.username)
                            put("password", a.password)
                            put("api_key", a.apiKey)
                            put("cookies", a.cookies)
                        })
                    }
                })
            }
        }
        return root.toString(2)
    }

    fun read(text: String): SettingsBackup {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupFormatException(BackupFormatException.Reason.INVALID)
        }
        if (root.optString("format") != MARKER || !root.has("version")) {
            throw BackupFormatException(BackupFormatException.Reason.INVALID)
        }
        val version = root.optInt("version", -1)
        if (version != VERSION) throw BackupFormatException(BackupFormatException.Reason.UNSUPPORTED_VERSION, version)
        val s = root.optJSONObject("settings") ?: JSONObject()
        val nfs = s.optJSONObject("nfs")?.let { n ->
            NfsSettings(
                enabled = n.optBoolean("enabled", false),
                server = n.optString("server", ""),
                export = n.optString("export", ""),
                uid = n.optInt("uid", NfsSettings.DEFAULT_UID),
                gid = n.optInt("gid", NfsSettings.DEFAULT_GID),
                subDir = n.optString("subdir", "")
            )
        }
        val settings = SettingsSnapshot(
            maxConcurrent = s.intOrNull("max_concurrent"),
            speedLimitMbit = s.doubleOrNull("speed_limit_mbit"),
            wifiOnly = s.booleanOrNull("wifi_only"),
            autoStartLinks = s.booleanOrNull("auto_start_links"),
            freeMode = s.booleanOrNull("free_mode"),
            exportToDownloads = s.booleanOrNull("export_to_downloads"),
            autoExtract = s.booleanOrNull("auto_extract"),
            flatExtract = s.booleanOrNull("flat_extract"),
            deleteArchiveAfterExtract = s.booleanOrNull("delete_archive_after_extract"),
            removeLinksAfterExtract = s.booleanOrNull("remove_links_after_extract"),
            passwordList = s.stringOrNull("archive_passwords"),
            extractExcludeList = s.stringOrNull("extract_excludes"),
            clickNLoadEnabled = s.booleanOrNull("clicknload_enabled"),
            themeMode = s.stringOrNull("theme_mode"),
            downloadTreeUri = s.stringOrNull("download_tree_uri"),
            nfs = nfs
        )
        val accounts = root.optJSONArray("accounts")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val a = array.optJSONObject(i) ?: return@mapNotNull null
                val hoster = a.stringOrNull("hoster") ?: return@mapNotNull null
                AccountBackup(
                    hosterId = hoster,
                    username = a.stringOrNull("username"),
                    password = a.stringOrNull("password"),
                    apiKey = a.stringOrNull("api_key"),
                    cookies = a.stringOrNull("cookies")
                )
            }
        }
        return SettingsBackup(
            createdAt = root.optLong("created_at", 0L),
            appVersion = root.optString("app_version", ""),
            settings = settings,
            accounts = accounts
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else opt(key)?.toString()

    private fun JSONObject.intOrNull(key: String): Int? =
        if (isNull(key)) null else (opt(key) as? Number)?.toInt()

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (isNull(key)) null else (opt(key) as? Number)?.toDouble()

    private fun JSONObject.booleanOrNull(key: String): Boolean? =
        if (isNull(key)) null else opt(key) as? Boolean
}

/** Reads and writes the live settings and accounts of the app. */
object SettingsBackups {

    suspend fun collect(app: JdApp, includeCredentials: Boolean): SettingsBackup {
        val repo = app.settings
        val snapshot = SettingsSnapshot(
            maxConcurrent = repo.currentMaxConcurrent(),
            speedLimitMbit = repo.speedLimitMbit.first(),
            wifiOnly = repo.currentWifiOnly(),
            autoStartLinks = repo.currentAutoStartLinks(),
            freeMode = repo.currentFreeMode(),
            exportToDownloads = repo.currentExportToDownloads(),
            autoExtract = repo.currentAutoExtract(),
            flatExtract = repo.currentFlatExtract(),
            deleteArchiveAfterExtract = repo.currentDeleteArchive(),
            removeLinksAfterExtract = repo.currentRemoveLinksAfterExtract(),
            passwordList = repo.passwordList.first(),
            extractExcludeList = repo.extractExcludeList.first(),
            clickNLoadEnabled = repo.currentClickNLoadEnabled(),
            themeMode = repo.themeMode.first(),
            downloadTreeUri = repo.currentDownloadTreeUri(),
            nfs = repo.currentNfs()
        )
        val accounts = if (includeCredentials) {
            app.db.accountDao().all().map {
                AccountBackup(
                    hosterId = it.hosterId,
                    username = it.username,
                    password = it.plainPassword,
                    apiKey = it.plainApiKey,
                    cookies = it.plainCookies
                )
            }
        } else null
        return SettingsBackup(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion(app),
            settings = snapshot,
            accounts = accounts
        )
    }

    /**
     * Applies [backup]; keys absent from the file stay as they are. The
     * target folder is only taken over while its persisted permission is
     * still held, since a reinstall loses it and a dead URI would fail
     * every download.
     */
    suspend fun restore(app: JdApp, backup: SettingsBackup): RestoreResult {
        val repo = app.settings
        val s = backup.settings
        var applied = 0
        suspend fun <T> apply(value: T?, set: suspend (T) -> Unit) {
            if (value != null) { set(value); applied++ }
        }
        apply(s.maxConcurrent, repo::setMaxConcurrent)
        apply(s.speedLimitMbit, repo::setSpeedLimitMbit)
        apply(s.wifiOnly, repo::setWifiOnly)
        apply(s.autoStartLinks, repo::setAutoStartLinks)
        apply(s.freeMode, repo::setFreeMode)
        apply(s.exportToDownloads, repo::setExportToDownloads)
        apply(s.autoExtract, repo::setAutoExtract)
        apply(s.flatExtract, repo::setFlatExtract)
        apply(s.deleteArchiveAfterExtract, repo::setDeleteArchiveAfterExtract)
        apply(s.removeLinksAfterExtract, repo::setRemoveLinksAfterExtract)
        apply(s.passwordList, repo::setPasswordList)
        apply(s.extractExcludeList, repo::setExtractExcludeList)
        apply(s.clickNLoadEnabled, repo::setClickNLoadEnabled)
        apply(s.themeMode, repo::setThemeMode)
        apply(s.downloadTreeUri?.takeIf { holdsWritePermission(app, it) }, repo::setDownloadTreeUri)
        apply(s.nfs, repo::setNfs)

        var accounts = 0
        backup.accounts?.let { imported ->
            val dao = app.db.accountDao()
            mergeAccounts(dao.all(), imported, Secrets::encrypt).forEach { account ->
                if (account.id == 0L) dao.insert(account) else dao.update(account)
                accounts++
            }
            if (accounts > 0) AccountRefresher.refreshStale(app)
        }
        return RestoreResult(applied, accounts)
    }

    /**
     * Accounts to write: an existing account with the same hoster and user
     * name gets the imported credentials and is re-checked, any other becomes
     * a new one. Unknown hosters and entries without credentials are dropped.
     */
    fun mergeAccounts(
        existing: List<Account>,
        imported: List<AccountBackup>,
        encrypt: (String?) -> String?
    ): List<Account> = imported
        .filter { it.hasCredentials && HosterRegistry.byId(it.hosterId) != null }
        .distinctBy { it.hosterId to it.username.normalized() }
        .map { a ->
            val match = existing.find { it.hosterId == a.hosterId && it.username.normalized() == a.username.normalized() }
            val base = match ?: Account(hosterId = a.hosterId, username = a.username?.ifBlank { null })
            base.copy(
                password = encrypt(a.password?.ifBlank { null }),
                apiKey = encrypt(a.apiKey?.ifBlank { null }),
                cookies = encrypt(a.cookies?.ifBlank { null }),
                valid = false,
                lastChecked = 0,
                statusText = null
            )
        }

    private fun String?.normalized(): String = orEmpty().trim().lowercase()

    private fun holdsWritePermission(context: Context, uri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isWritePermission }

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
}
