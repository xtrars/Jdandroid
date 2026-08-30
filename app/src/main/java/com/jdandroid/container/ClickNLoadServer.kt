package com.jdandroid.container

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method

/**
 * Lokaler Click'n'Load-2-Server auf Port 9666. Browser-Seiten mit
 * "Click'n'Load"-Button senden die (verschlüsselten) Links hierher;
 * sie werden lokal entschlüsselt und über [onLinks] eingereiht.
 *
 * Funktioniert mit Browsern auf demselben Gerät (localhost) – wie beim
 * JDownloader am Desktop.
 */
class ClickNLoadServer(
    private val onLinks: (List<String>) -> Unit
) : NanoHTTPD("127.0.0.1", PORT) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                // Muss als JavaScript ausgeliefert werden: bei text/html
                // verweigern Browser die Ausfuehrung und die Seite haelt den
                // Downloadmanager fuer nicht vorhanden.
                "/jdcheck.js" -> newFixedLengthResponse(
                    Response.Status.OK, "text/javascript",
                    "jdownloader=true; var jd_version='JDAndroid';"
                )
                "/crossdomain.xml" -> newFixedLengthResponse(
                    Response.Status.OK, "application/xml",
                    """<?xml version="1.0"?><cross-domain-policy>""" +
                        """<allow-access-from domain="*"/></cross-domain-policy>"""
                )
                "/flash", "/flash/", "/jdcheck" -> newFixedLengthResponse("JDAndroid")
                "/flashgot", "/flash/add", "/flash/addcrypted", "/flash/addcrypted2" ->
                    handleAdd(session)
                else -> newFixedLengthResponse("JDAndroid")
            }
        } catch (e: Exception) {
            Log.w("ClickNLoad", "Fehler bei ${session.uri}: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "failed ${e.message}"
            )
        }.also { it.addHeader("Access-Control-Allow-Origin", "*") }
    }

    private fun handleAdd(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT) {
            session.parseBody(body)
        }
        val params = session.parameters.mapValues { it.value.firstOrNull() ?: "" }

        val crypted = params["crypted"]
        val jk = params["jk"]
        val plainUrls = params["urls"] ?: params["links"]

        val links = when {
            crypted != null && jk != null ->
                ContainerDecrypter.decryptClickNLoad(crypted, jk)
            !plainUrls.isNullOrBlank() ->
                plainUrls.split('\n', '\r', ' ').filter { it.startsWith("http") }
            else -> emptyList()
        }

        if (links.isNotEmpty()) onLinks(links)
        return newFixedLengthResponse("success\r\n")
    }

    companion object {
        const val PORT = 9666
    }
}
