package com.jdandroid.container

import com.jdandroid.core.Texts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Die deutsche Map in [ContainerTexts] muss wortgleich in
 * `values/strings_service.xml` stehen und in `values-en/strings_service.xml`
 * uebersetzt sein - sonst saehe der Nutzer auf dem Geraet einen anderen Text
 * als die Unit-Tests.
 */
class ContainerTextsTest {

    @After
    fun providerEntfernen() = Texts.install(null)

    @Test
    fun mapDeckungsgleichMitRessourcen() {
        val de = strings(File(resDir(), "values/strings_service.xml"))
        val en = strings(File(resDir(), "values-en/strings_service.xml"))
        ContainerTexts.texts.forEach { (key, text) ->
            assertEquals("values/strings_service.xml: Text zu '$key' weicht von der Map ab", text, de[key])
            assertFalse("values-en/strings_service.xml: Uebersetzung zu '$key' fehlt", en[key].isNullOrBlank())
        }
    }

    @Test
    fun ohneProviderDeutschMitArgumenten() {
        Texts.install(null)
        assertEquals("Server antwortet (HTTP 200).", ContainerTexts.t("service_cnl_selftest_ok", 200))
        assertEquals("1 Link übernommen", ContainerTexts.quantity(
            "service_cnl_result_links_taken_one", "service_cnl_result_links_taken_other", 1
        ))
        assertEquals("3 Links übernommen", ContainerTexts.quantity(
            "service_cnl_result_links_taken_one", "service_cnl_result_links_taken_other", 3
        ))
        assertEquals("service_fremd", ContainerTexts.t("service_fremd"))
    }

    @Test
    fun providerHatVorrang() {
        Texts.install { key, args -> if (key == "service_cnl_selftest_ok") "Server responds (HTTP ${args[0]})." else null }
        assertEquals("Server responds (HTTP 200).", ContainerTexts.t("service_cnl_selftest_ok", 200))
        // Unbekannt beim Provider: deutscher Standardtext
        assertEquals("Datei nicht lesbar", ContainerTexts.t("service_dlc_file_unreadable"))
    }

    private fun resDir(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Ressourcenordner nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
    }

    private fun strings(file: File): Map<String, String> {
        assertTrue("Ressourcendatei fehlt: $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val result = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            result[e.getAttribute("name")] = e.textContent
                .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
        return result
    }
}
