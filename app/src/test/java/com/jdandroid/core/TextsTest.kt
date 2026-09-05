package com.jdandroid.core

import com.jdandroid.core.texts.EngineTexts
import com.jdandroid.core.texts.HosterTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Kotlin fallback maps and the string resources must match both ways:
 * every map key exists verbatim in `values/strings_<area>.xml` and translated
 * in `values-en/`, and the map knows every key of the file, so unit tests
 * see the same texts as the device.
 */
class TextsTest {

    @After
    fun providerEntfernen() = Texts.install(null)

    @Test
    fun engineTexteDeckungsgleich() = pruefeBereich("strings_engine.xml", EngineTexts.texts)

    @Test
    fun hosterTexteDeckungsgleich() = pruefeBereich("strings_hoster.xml", HosterTexts.texts)

    @Test
    fun schluesselschemaJeBereich() {
        EngineTexts.texts.keys.forEach { assertTrue("Engine-Schluessel '$it' muss mit engine_ beginnen", it.startsWith("engine_")) }
        HosterTexts.texts.keys.forEach { assertTrue("Hoster-Schluessel '$it' muss mit hoster_ beginnen", it.startsWith("hoster_")) }
        assertTrue(EngineTexts.texts.keys.none { it in HosterTexts.texts })
    }

    @Test
    fun ohneProviderKommtDerDeutscheText() {
        Texts.install(null)
        // Unknown key: the key itself, no crash
        assertEquals("engine_unbekannt", Texts.t("engine_unbekannt"))
        Texts.fallbackKeys().forEach { key ->
            assertEquals(Texts.fallback(key), Texts.t(key))
        }
    }

    @Test
    fun providerHatVorrang() {
        Texts.install { key, args -> if (key == "engine_test") "Test ${args[0]} von ${args[1]}" else null }
        assertEquals("Test 1 von x", Texts.t("engine_test", 1, "x"))
        // Provider miss falls back to the map, then to the key
        assertEquals("engine_fremd", Texts.t("engine_fremd"))
    }

    private fun pruefeBereich(datei: String, map: Map<String, String>) {
        val de = strings(File(resDir(), "values/$datei"))
        val en = strings(File(resDir(), "values-en/$datei"))
        map.forEach { (key, text) ->
            assertEquals("values/$datei: Text zu '$key' weicht von der Kotlin-Map ab", text, de[key])
            assertFalse("values-en/$datei: Uebersetzung zu '$key' fehlt", en[key].isNullOrBlank())
        }
        de.keys.forEach { key ->
            assertTrue("'$key' steht in values/$datei, fehlt aber in der Kotlin-Map", key in map)
        }
        en.keys.forEach { key ->
            assertTrue("'$key' steht in values-en/$datei, aber nicht in values/$datei", key in de)
        }
    }

    private fun resDir(): File {
        // Gradle runs unit tests in app/; also resolve from the project root
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Ressourcenordner nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
    }

    /** name -> text of all <string> elements, Android escapes resolved. */
    private fun strings(file: File): Map<String, String> {
        assertTrue("Ressourcendatei fehlt: $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val result = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            result[e.getAttribute("name")] = unescape(e.textContent)
        }
        return result
    }

    private fun unescape(s: String): String = s
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\@", "@")
        .replace("\\?", "?")
        .replace("\\\\", "\\")
}
