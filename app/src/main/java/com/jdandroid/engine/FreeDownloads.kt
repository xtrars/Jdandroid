package com.jdandroid.engine

import com.jdandroid.hoster.FreeHints
import java.util.concurrent.ConcurrentHashMap

/**
 * Captcha-Seite eines wartenden Eintrags samt der Session-Cookies, die der
 * Hoster dem Browser mitgibt (Set-Cookie-Zeilen fuer [cookieUrl]).
 */
data class CaptchaPage(
    val url: String,
    val cookieUrl: String? = null,
    val cookies: List<String> = emptyList()
)

/**
 * Prozessweiter Zustand des Free-Modus je Eintrag: die Captcha-Seite, auf
 * der ein Download haengt (mit den Cookies des Hoster-Ablaufs), und die aus
 * dem Browser uebernommenen Hinweise (Direktlink, Cookies) fuer den naechsten
 * Versuch. Bewusst nicht in der Datenbank: ein Direktlink ist Minuten
 * gueltig, ein Neustart des Prozesses darf ihn vergessen (die Captcha-Ansicht
 * faellt dann auf die Link-URL zurueck).
 */
object FreeDownloads {
    private val captchaPages = ConcurrentHashMap<Long, CaptchaPage>()
    private val hints = ConcurrentHashMap<Long, FreeHints>()

    fun captchaRequired(id: Long, page: CaptchaPage) { captchaPages[id] = page }

    fun captchaPage(id: Long): CaptchaPage? = captchaPages[id]

    /** Hinweise aus dem Browser fuer den naechsten Versuch hinterlegen. */
    fun putHints(id: Long, value: FreeHints) {
        hints[id] = value
        captchaPages.remove(id)
    }

    /** Hinweise einmalig abholen (der Direktlink gilt nur fuer einen Versuch). */
    fun takeHints(id: Long): FreeHints? = hints.remove(id)

    fun forget(id: Long) {
        captchaPages.remove(id)
        hints.remove(id)
    }
}
