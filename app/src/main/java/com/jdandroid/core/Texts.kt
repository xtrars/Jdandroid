package com.jdandroid.core

import com.jdandroid.core.texts.EngineTexts
import com.jdandroid.core.texts.HosterTexts
import java.util.Locale

/**
 * Uebersetzte Texte fuer Schichten ohne Android-Context (Engine, Extractor,
 * Hoster, FreeMode, Format). Ein Schluessel ist zugleich der Name einer
 * String-Ressource (`strings_engine.xml`, `strings_hoster.xml`):
 *
 * - Auf dem Geraet installiert [com.jdandroid.JdApp] einen [Provider], der
 *   den Schluessel ueber die Ressourcen der aktuellen Sprache aufloest.
 * - Ohne Provider (JVM-Unit-Tests) kommen die deutschen Texte aus den
 *   Kotlin-Maps [EngineTexts] und [HosterTexts]. `TextsTest` haelt Maps und
 *   Ressourcen deckungsgleich.
 *
 * Platzhalter wie in Android: `%1$s`, `%2$d`. Ein unbekannter Schluessel
 * liefert den Schluessel selbst zurueck, damit ein Tippfehler sichtbar wird
 * statt abzustuerzen.
 */
object Texts {

    /** Loest einen Schluessel samt Argumenten auf; null = Schluessel unbekannt. */
    fun interface Provider {
        fun text(key: String, args: Array<out Any>): String?
    }

    /** Deutsche Standardtexte je Bereich; die Reihenfolge ist die Suchreihenfolge. */
    private val fallbacks: List<Map<String, String>> = listOf(EngineTexts.texts, HosterTexts.texts)

    @Volatile
    private var provider: Provider? = null

    /** Provider setzen (Android) oder mit null entfernen (Tests). */
    fun install(provider: Provider?) {
        this.provider = provider
    }

    /** Deutscher Text aus den Kotlin-Maps, null wenn kein Bereich den Schluessel kennt. */
    fun fallback(key: String): String? = fallbacks.firstNotNullOfOrNull { it[key] }

    /** Alle Schluessel der Kotlin-Maps (fuer den Abgleich mit den Ressourcen). */
    fun fallbackKeys(): Set<String> = fallbacks.flatMapTo(LinkedHashSet()) { it.keys }

    /** Text zu [key], mit [args] wie `String.format` formatiert. */
    fun t(key: String, vararg args: Any): String {
        provider?.text(key, args)?.let { return it }
        val raw = fallback(key) ?: return key
        return if (args.isEmpty()) raw else String.format(Locale.GERMANY, raw, *args)
    }
}
