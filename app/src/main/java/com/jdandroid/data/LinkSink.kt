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

    /** Liefert die Anzahl tatsächlich neu hinzugefügter Downloads. */
    suspend fun addFromText(context: Context, text: String): Int {
        val dao = (context.applicationContext as JdApp).db.downloadDao()
        var added = 0
        LinkParser.parse(text).forEach { (url, hoster) ->
            if (dao.countByUrl(url) == 0) {
                dao.insert(DownloadItem(url = url, hosterId = hoster.id))
                added++
            }
        }
        if (added > 0) {
            DownloadService.send(context, DownloadService.ACTION_PUMP)
        }
        return added
    }

    suspend fun addUrls(context: Context, urls: List<String>): Int =
        addFromText(context, urls.joinToString("\n"))
}
