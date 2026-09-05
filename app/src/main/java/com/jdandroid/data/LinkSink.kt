package com.jdandroid.data

import android.content.Context
import androidx.room.withTransaction
import com.jdandroid.JdApp
import com.jdandroid.hoster.LinkParser

/**
 * Single entry point for queuing links, used by the UI, the Click'n'Load
 * server and the DLC import. Filters for supported hosters and skips
 * duplicates.
 */
object LinkSink {

    /**
     * Called after queuing with auto-start so the download service starts.
     * The data layer does not know the engine; [com.jdandroid.JdApp] sets it.
     */
    @Volatile
    var onQueued: (Context) -> Unit = {}

    /**
     * Returns the number of downloads actually added. All links of one call
     * share a package.
     *
     * @param source origin (e.g. the web page for Click'n'Load), shown on the package.
     * @param passwords extraction passwords supplied with the links; added to the password list.
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

        val parsed = LinkParser.parse(text)
        if (parsed.isEmpty()) return 0

        // Links go to the link grabber and are checked there; "Start" queues
        // them, unless auto-start is enabled.
        val autoStart = app.settings.currentAutoStartLinks()
        val status = if (autoStart) DownloadStatus.QUEUED else DownloadStatus.COLLECTED

        // Duplicate check and insert in one transaction: Click'n'Load pages
        // often send twice (form + XHR), which would create two packages with
        // the same links. The unique index on url catches the rest (insert
        // returns -1).
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
            // No archiveKey without a file name; both arrive with the link
            // check or resolving (DownloadDao.setFileName).
            val inserted = links.mapNotNull { (url, hoster) ->
                dao.insert(DownloadItem(url = url, hosterId = hoster.id, packageId = packageId, status = status))
                    .takeIf { it > 0 }
            }
            if (inserted.isEmpty()) packageDao.delete(packageId)
            inserted
        }
        if (ids.isEmpty()) return 0
        if (passwords.isNotEmpty()) app.settings.addPasswords(passwords)
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

    /** Host of a URL ("example.com"), otherwise the trimmed text. */
    fun displaySource(source: String): String {
        val host = Regex("""^https?://([^/:?#]+)""", RegexOption.IGNORE_CASE)
            .find(source.trim())?.groupValues?.get(1)
        return (host ?: source.trim()).removePrefix("www.").take(80)
    }
}
