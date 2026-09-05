package com.jdandroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every string resource file exists in both languages with the same keys;
 * a text with format arguments uses the same placeholders (%1$s, %2$d …) in
 * German and English, otherwise String.format throws on the device in one
 * language only. Plurals need "one" and "other" in both languages.
 */
class ResourcePlaceholderTest {

    private val placeholder = Regex("""%(\d+\$)?[sdf]""")

    @Test
    fun jedeDeutscheDateiHatEinEnglischesGegenstueckMitDenselbenSchluesseln() {
        val files = File(resDir(), "values").listFiles { f -> f.name.startsWith("strings_") }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        files.forEach { de ->
            val en = File(resDir(), "values-en/${de.name}")
            assertTrue("values-en/${de.name} fehlt", en.isFile)
            assertEquals("Schluessel in ${de.name}", strings(de).keys, strings(en).keys)
            assertEquals("Plurals in ${de.name}", plurals(de).keys, plurals(en).keys)
        }
    }

    @Test
    fun platzhalterSindInBeidenSprachenGleich() {
        forEachFile { name, de, en ->
            strings(de).forEach { (key, text) ->
                val expected = placeholders(text)
                val actual = placeholders(strings(en).getValue(key))
                assertEquals("$name/$key: Platzhalter weichen ab", expected, actual)
                // Two or more arguments must be numbered, otherwise translations cannot reorder them
                if (expected.size > 1) assertTrue("$name/$key: Platzhalter ohne Nummer", expected.all { it.contains('$') })
            }
        }
    }

    @Test
    fun pluralsHabenOneUndOtherMitGleichenPlatzhaltern() {
        var seen = 0
        forEachFile { name, de, en ->
            val deP = plurals(de)
            val enP = plurals(en)
            deP.forEach { (key, items) ->
                seen++
                assertTrue("$name/$key: quantity one/other fehlt", items.keys.containsAll(listOf("one", "other")))
                assertTrue("values-en/$name/$key: quantity one/other fehlt", enP.getValue(key).keys.containsAll(listOf("one", "other")))
                val expected = placeholders(items.getValue("other"))
                (items.values + enP.getValue(key).values).forEach { text ->
                    val actual = placeholders(text)
                    // "one" may drop the count (e.g. "one file"), never use a different one
                    assertTrue("$name/$key: Platzhalter $actual passen nicht zu $expected", expected.containsAll(actual))
                }
            }
        }
        assertTrue("keine Plurals gefunden", seen > 0)
    }

    private fun placeholders(text: String): Set<String> = placeholder.findAll(text).map { it.value }.toSet()

    private fun forEachFile(block: (String, File, File) -> Unit) {
        File(resDir(), "values").listFiles { f -> f.name.startsWith("strings_") }!!.sortedBy { it.name }.forEach { de ->
            block(de.name, de, File(resDir(), "values-en/${de.name}"))
        }
    }

    private fun resDir(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Ressourcenordner nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
    }

    private fun document(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun strings(file: File): Map<String, String> {
        val nodes = document(file).getElementsByTagName("string")
        val result = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            result[e.getAttribute("name")] = e.textContent
        }
        return result
    }

    /** name -> (quantity -> text). */
    private fun plurals(file: File): Map<String, Map<String, String>> {
        val nodes = document(file).getElementsByTagName("plurals")
        val result = LinkedHashMap<String, Map<String, String>>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            val items = e.getElementsByTagName("item")
            val byQuantity = LinkedHashMap<String, String>()
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                byQuantity[item.getAttribute("quantity")] = item.textContent
            }
            result[e.getAttribute("name")] = byQuantity
        }
        return result
    }
}
