package com.jdandroid.container

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method

/**
 * Eine ueber Click'n'Load eingegangene Anfrage: Links plus die Zusatzdaten,
 * die das Protokoll mitliefert (Paketname, Entpack-Passwoerter, Ursprung).
 */
data class CnlRequest(
    val urls: List<String>,
    val packageName: String? = null,
    val passwords: List<String> = emptyList(),
    val source: String? = null
)

/**
 * Lokaler Click'n'Load-2-Server auf Port 9666. Browser-Seiten mit
 * "Click'n'Load"-Button senden die (verschlüsselten) Links hierher;
 * sie werden lokal entschlüsselt und über [onRequest] eingereiht.
 *
 * Funktioniert mit Browsern auf demselben Gerät (localhost) – wie beim
 * JDownloader am Desktop. Der Server bindet ausschliesslich an Loopback.
 */
class ClickNLoadServer(
    private val hostname: String = LOOPBACK,
    private val onRequest: (CnlRequest) -> Unit
) : NanoHTTPD(hostname, PORT) {

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            when {
                // CORS-Preflight: neuere Chrome-Versionen (Local Network Access)
                // fragen vor fetch/XHR an localhost per OPTIONS nach.
                session.method == Method.OPTIONS ->
                    newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
                session.uri == "/jdcheck.js" -> newFixedLengthResponse(
                    // Muss als JavaScript ausgeliefert werden: bei text/html
                    // verweigern Browser die Ausfuehrung und die Seite haelt den
                    // Downloadmanager fuer nicht vorhanden.
                    Response.Status.OK, "text/javascript",
                    "jdownloader=true; var jd_version='JDAndroid';"
                )
                session.uri == "/crossdomain.xml" -> newFixedLengthResponse(
                    Response.Status.OK, "application/xml",
                    """<?xml version="1.0"?><cross-domain-policy>""" +
                        """<allow-access-from domain="*"/></cross-domain-policy>"""
                )
                session.uri in ADD_PATHS -> handleAdd(session)
                else -> newFixedLengthResponse("JDAndroid")
            }
        } catch (e: ContainerDecrypter.ContainerException) {
            Log.w("ClickNLoad", "Abgelehnt bei ${session.uri}: ${e.message}")
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "failed\r\n")
        } catch (e: Exception) {
            Log.w("ClickNLoad", "Fehler bei ${session.uri}: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "failed\r\n")
        }
        return response.withCors(session)
    }

    private fun Response.withCors(session: IHTTPSession): Response {
        // Origin zurueckspiegeln, sonst lehnt der Browser Antworten mit
        // Credentials ab; ohne Origin (Formular-POST) bleibt "*".
        addHeader("Access-Control-Allow-Origin", session.headers["origin"] ?: "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type, X-Requested-With")
        addHeader("Access-Control-Allow-Private-Network", "true")
        addHeader("Access-Control-Max-Age", "86400")
        return this
    }

    private fun handleAdd(session: IHTTPSession): Response {
        // Jede im Browser geoeffnete Seite darf hierher senden: die Groesse
        // begrenzen, sonst laesst ein 50-MB-Koerper die App per OOM abstuerzen.
        val length = session.headers["content-length"]?.trim()?.toLongOrNull()
        if (length != null && length > MAX_BODY_BYTES) {
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, MIME_PLAINTEXT, "failed\r\n")
        }
        val body = HashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT) {
            session.parseBody(body)
        }
        val params = session.parameters.mapValues { it.value.firstOrNull() ?: "" }

        val crypted = params["crypted"]
        val jk = params["jk"]
        val plainUrls = params["urls"] ?: params["links"]

        val links = when {
            // Click'n'Load 2: AES-verschluesselte Linkliste, Schluessel in jk
            !crypted.isNullOrBlank() && !jk.isNullOrBlank() ->
                ContainerDecrypter.decryptClickNLoad(crypted, jk)
            // Aeltere Variante (/flash/addcrypted): kompletter DLC-Container
            !crypted.isNullOrBlank() ->
                ContainerDecrypter.decryptDlc(crypted)
            !plainUrls.isNullOrBlank() ->
                plainUrls.split('\n', '\r', ' ').filter { it.startsWith("http") }
            else -> emptyList()
        }

        if (links.isEmpty()) {
            // Wie JDownloader: die Seite zeigt dann "fehlgeschlagen" statt
            // faelschlich Erfolg zu melden.
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "failed\r\n")
        }

        // Paketname und Passwoerter kappen: eine Seite darf die Passwortliste
        // nicht unbegrenzt fuellen (jede Extraktion probiert alle Eintraege)
        val passwords = params["passwords"].orEmpty()
            .split('\n', '\r')
            .map { it.trim().take(MAX_PASSWORD_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_PASSWORDS)
        onRequest(
            CnlRequest(
                urls = links.take(MAX_LINKS),
                packageName = params["package"]?.trim()?.take(MAX_PACKAGE_LENGTH)?.ifBlank { null },
                passwords = passwords,
                source = params["source"]?.trim()?.ifBlank { null }
                    ?: session.headers["referer"]?.takeIf { it.isNotBlank() }
            )
        )
        return newFixedLengthResponse("success\r\n")
    }

    companion object {
        const val PORT = 9666
        const val LOOPBACK = "127.0.0.1"
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        const val MAX_PACKAGE_LENGTH = 200
        const val MAX_PASSWORDS = 50
        const val MAX_PASSWORD_LENGTH = 200
        const val MAX_LINKS = 5000
        private val ADD_PATHS = setOf(
            "/flashgot", "/flash/add", "/flash/addcrypted", "/flash/addcrypted2"
        )
    }
}
