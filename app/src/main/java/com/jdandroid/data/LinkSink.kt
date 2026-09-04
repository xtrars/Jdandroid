package com.jdandroid.data

import android.content.Context
import androidx.room.withTransaction
import com.jdandroid.JdApp
import com.jdandroid.hoster.LinkParser

/**
 * Zentrale Stelle zum Einreihen von Links – genutzt von der UI, vom
 * Click'n'Load-Server und vom DLC-Import. Filtert auf unterstützte Hoster
 * und überspringt Duplikate.
 */
object LinkSink {

    /**
     * Wird nach dem Einreihen mit Sofortstart aufgerufen, damit der
     * Download-Dienst anlaeuft. Die Datenschicht kennt die Engine nicht;
     * [com.jdandroid.JdApp] setzt den Aufruf beim Start.
     */
    @Volatile
    var onQueued: (Context) -> Unit = {}

    /**
     * Liefert die Anzahl tatsächlich neu hinzugefügter Downloads. Alle Links
     * eines Aufrufs landen wie im JDownloader in einem gemeinsamen Paket.
     *
     * @param source Herkunft (z.B. Webseite bei Click'n'Load), wird am Paket angezeigt.
     * @param passwords Entpack-Passwörter, die mitgeliefert wurden; sie werden
     *   der Passwortliste hinzugefügt.
     */
    suspend fun addFromText(
        context: Context,
        text: String,
        packageName: String? = null,
        source: String? = null,
        passwords: List<String> = emptyList()
    ): Int {
        val app = context.applicationContext as JdApp
        val dao = app.db.downloadDao()
        val packageDao = app.db.packageDao()

        if (passwords.isNotEmpty()) app.settings.addPasswords(passwords)

        val parsed = LinkParser.parse(text)
        if (parsed.isEmpty()) return 0

        // Wie im JDownloader: Links landen zuerst im Linksammler und werden
        // dort online geprueft; erst "Starten" reiht sie ein. Optional sofort.
        val autoStart = app.settings.currentAutoStartLinks()
        val status = if (autoStart) DownloadStatus.QUEUED else DownloadStatus.COLLECTED

        // Duplikatpruefung und Einfuegen in EINER Transaktion: Click'n'Load-
        // Seiten senden oft doppelt (Formular + XHR) - sonst entstehen zwei
        // Pakete mit denselben Links. Der eindeutige Index auf url faengt den
        // Rest (insert liefert dann -1).
        val ids = app.db.withTransaction {
            val links = parsed.filter { dao.countByUrl(it.first) == 0 }
            if (links.isEmpty()) return@withTransaction emptyList()
            val name = packageName?.takeIf { it.isNotBlank() }
                ?: PackageNaming.suggestFromUrls(links.map { it.first })
            val packageId = packageDao.insert(
                DownloadPackage(
                    name = name,
                    autoNamed = packageName.isNullOrBlank(),
                    source = source?.let { displaySource(it) }
                )
            )
            val inserted = links.mapNotNull { (url, hoster) ->
                dao.insert(DownloadItem(url = url, hosterId = hoster.id, packageId = packageId, status = status))
                    .takeIf { it > 0 }
            }
            if (inserted.isEmpty()) packageDao.delete(packageId)
            inserted
        }
        if (ids.isEmpty()) return 0
        if (autoStart) onQueued(context)
        else LinkChecker.schedule(app, ids)
        return ids.size
    }

    suspend fun addUrls(
        context: Context,
        urls: List<String>,
        packageName: String? = null,
        source: String? = null,
        passwords: List<String> = emptyList()
    ): Int = addFromText(context, urls.joinToString("\n"), packageName, source, passwords)

    /** Aus einer URL nur den Host anzeigen ("example.com"), sonst den Text gekuerzt. */
    fun displaySource(source: String): String {
        val host = Regex("""^https?://([^/:?#]+)""", RegexOption.IGNORE_CASE)
            .find(source.trim())?.groupValues?.get(1)
        return (host ?: source.trim()).removePrefix("www.").take(80)
    }
}
