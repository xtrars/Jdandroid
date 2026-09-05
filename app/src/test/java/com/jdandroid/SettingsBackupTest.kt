package com.jdandroid

import com.jdandroid.data.Account
import com.jdandroid.data.AccountBackup
import com.jdandroid.data.BackupFormatException
import com.jdandroid.data.NfsSettings
import com.jdandroid.data.SettingsBackup
import com.jdandroid.data.SettingsBackupFormat
import com.jdandroid.data.SettingsBackups
import com.jdandroid.data.SettingsSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backup file format: round trip, version check, tolerance for missing keys, account merge. */
class SettingsBackupTest {

    private val full = SettingsSnapshot(
        maxConcurrent = 4,
        speedLimitMbit = 12.5,
        wifiOnly = true,
        autoStartLinks = false,
        freeMode = false,
        exportToDownloads = true,
        autoExtract = false,
        flatExtract = true,
        deleteArchiveAfterExtract = false,
        removeLinksAfterExtract = true,
        passwordList = "geheim\nzwei",
        extractExcludeList = "*.nfo\nproof/*",
        clickNLoadEnabled = true,
        themeMode = "dark",
        downloadTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        nfs = NfsSettings(enabled = true, server = "nas.local", export = "/volume1/dl", uid = 1026, gid = 100, subDir = "jd")
    )

    private val accounts = listOf(
        AccountBackup("rapidgator", username = "me@example.org", password = "pw"),
        AccountBackup("onefichier", apiKey = "key-123"),
        AccountBackup("ddownload", cookies = "xfss=abc; cf_clearance=def")
    )

    @Test
    fun rundreiseErhaeltAlleWerte() {
        val backup = SettingsBackup(createdAt = 1_725_000_000_000L, appVersion = "0.3.0", settings = full, accounts = accounts)
        val text = SettingsBackupFormat.write(backup)
        val read = SettingsBackupFormat.read(text)
        assertEquals(backup, read)
        assertEquals(16, read.settings.count)
        assertEquals(SettingsBackupFormat.VERSION, JSONObject(text).getInt("version"))
    }

    @Test
    fun ohneZugangsdatenFehltDerKontenschluessel() {
        val text = SettingsBackupFormat.write(SettingsBackup(0L, "0.3.0", full, accounts = null))
        assertFalse(JSONObject(text).has("accounts"))
        assertNull(SettingsBackupFormat.read(text).accounts)
        // Included but empty stays an empty list, not "absent"
        val empty = SettingsBackupFormat.write(SettingsBackup(0L, "0.3.0", full, accounts = emptyList()))
        assertEquals(emptyList<AccountBackup>(), SettingsBackupFormat.read(empty).accounts)
    }

    @Test
    fun fehlendeSchluesselBleibenNull() {
        val text = """{"format":"jdandroid-backup","version":1,"settings":{"max_concurrent":3,"nfs":{"server":"nas"}}}"""
        val read = SettingsBackupFormat.read(text)
        assertEquals(3, read.settings.maxConcurrent)
        assertNull(read.settings.wifiOnly)
        assertNull(read.settings.passwordList)
        assertNull(read.settings.downloadTreeUri)
        assertEquals(NfsSettings(server = "nas"), read.settings.nfs)
        assertEquals(2, read.settings.count)
        assertNull(read.accounts)
        assertEquals("", read.appVersion)
    }

    @Test
    fun jsonNullZaehltAlsFehlend() {
        val text = """{"format":"jdandroid-backup","version":1,"settings":{"theme_mode":null,"wifi_only":"nein"}}"""
        val read = SettingsBackupFormat.read(text)
        assertNull(read.settings.themeMode)
        // Wrong type is ignored rather than crashing the restore
        assertNull(read.settings.wifiOnly)
        assertEquals(0, read.settings.count)
    }

    @Test
    fun unbekannteVersionWirdAbgelehnt() {
        val e = assertThrows(BackupFormatException::class.java) {
            SettingsBackupFormat.read("""{"format":"jdandroid-backup","version":2,"settings":{}}""")
        }
        assertEquals(BackupFormatException.Reason.UNSUPPORTED_VERSION, e.reason)
        assertEquals(2, e.version)
    }

    @Test
    fun fremdeOderKaputteDateiIstUngueltig() {
        listOf("", "nicht json", "[1,2]", """{"version":1}""", """{"format":"other","version":1}""").forEach { text ->
            val e = assertThrows(text, BackupFormatException::class.java) { SettingsBackupFormat.read(text) }
            assertEquals(text, BackupFormatException.Reason.INVALID, e.reason)
        }
    }

    @Test
    fun kontenOhneHosterOderZugangsdatenWerdenUebersprungen() {
        val text = """{"format":"jdandroid-backup","version":1,"settings":{},
            "accounts":[{"username":"x","password":"y"},{"hoster":"rapidgator","username":"a","password":"b"},7]}"""
        val read = SettingsBackupFormat.read(text)
        assertEquals(listOf(AccountBackup("rapidgator", username = "a", password = "b")), read.accounts)
    }

    @Test
    fun zusammenfuehrenAktualisiertNachHosterUndBenutzername() {
        val existing = listOf(
            Account(id = 5, hosterId = "rapidgator", username = "Me@example.org", password = "enc:alt", valid = true, statusText = "Premium"),
            Account(id = 6, hosterId = "onefichier", apiKey = "enc:alt")
        )
        val merged = SettingsBackups.mergeAccounts(existing, accounts) { it?.let { v -> "enc:$v" } }
        assertEquals(3, merged.size)
        val rg = merged.single { it.hosterId == "rapidgator" }
        assertEquals(5L, rg.id)
        assertEquals("enc:pw", rg.password)
        assertFalse(rg.valid)
        assertEquals(0L, rg.lastChecked)
        assertNull(rg.statusText)
        assertEquals(6L, merged.single { it.hosterId == "onefichier" }.id)
        assertEquals("enc:key-123", merged.single { it.hosterId == "onefichier" }.apiKey)
        val dd = merged.single { it.hosterId == "ddownload" }
        assertEquals(0L, dd.id)
        assertEquals("enc:xfss=abc; cf_clearance=def", dd.cookies)
        assertNull(dd.username)
    }

    @Test
    fun zusammenfuehrenVerwirftUnbekannteHosterUndLeereKonten() {
        val imported = listOf(
            AccountBackup("unknown", username = "a", password = "b"),
            AccountBackup("rapidgator", username = "a"),
            AccountBackup("rapidgator", username = "a", password = "1"),
            AccountBackup("rapidgator", username = "A", password = "2")
        )
        val merged = SettingsBackups.mergeAccounts(emptyList(), imported) { it }
        assertEquals(1, merged.size)
        assertTrue(merged.single().password == "1")
    }
}
