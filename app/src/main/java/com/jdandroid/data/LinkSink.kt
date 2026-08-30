package com.jdandroid.data

import android.content.Context
import com.jdandroid.JdApp
import com.jdandroid.engine.DownloadService
import com.jdandroid.hoster.LinkParser

/**
 * Zentrale Stelle zum Einreihen von Links – genutzt von der UI, vom
 * Click'n'Load-Server und vom DLC-Import. Filtert auf unterstützte Hoster
 * und überspringt Duplikate.
 */
object LinkSink {

    /**
     * Liefert die Anzahl tatsächlich neu hinzugefügter Downloads. Alle Links
     * eines Aufrufs landen wie im JDownloader in einem gemeinsamen Paket.
     */
    suspend fun addFromText(context: Context, text: String, packageName: String? = null): Int {
        val app = context.applicationContext as JdApp
        val dao = app.db.downloadDao()
        val packageDao = app.db.packageDao()

        val links = LinkParser.parse(text).filter { dao.countByUrl(it.first) == 0 }
        if (links.isEmpty()) return 0

        val name = packageName?.takeIf { it.isNotBlank() }
            ?: PackageNaming.suggestFromUrls(links.map { it.first })
        val packageId = packageDao.insert(
            DownloadPackage(name = name, autoNamed = packageName.isNullOrBlank())
        )

        links.forEach { (url, hoster) ->
            dao.insert(DownloadItem(url = url, hosterId = hoster.id, packageId = packageId))
        }
        DownloadService.send(context, DownloadService.ACTION_PUMP)
        return links.size
    }

    suspend fun addUrls(context: Context, urls: List<String>, packageName: String? = null): Int =
        addFromText(context, urls.joinToString("\n"), packageName)
}
