package com.jdandroid

import com.jdandroid.hoster.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {

    @Test
    fun `erkennt alle drei Hoster aus gemischtem Text`() {
        val text = """
            Hier die Links:
            https://rapidgator.net/file/abc123/datei.rar.html
            https://1fichier.com/?xyz789
            https://ddownload.com/a1b2c3d4e5f6/name.zip
            https://example.com/nicht-unterstuetzt
        """.trimIndent()

        val links = LinkParser.parse(text)
        assertEquals(3, links.size)
        assertEquals(
            setOf("rapidgator", "onefichier", "ddownload"),
            links.map { it.second.id }.toSet()
        )
    }

    @Test
    fun `rg_to und ddl_to Kurzdomains werden erkannt`() {
        val links = LinkParser.parse(
            "https://rg.to/file/aabbcc/x.zip https://ddl.to/d4e5f6a1b2c3"
        )
        assertEquals(2, links.size)
    }

    @Test
    fun `Satzzeichen am Linkende werden entfernt`() {
        val links = LinkParser.parse("Schau mal: https://1fichier.com/?abc123, danke!")
        assertEquals(1, links.size)
        assertEquals("https://1fichier.com/?abc123", links[0].first)
    }

    @Test
    fun `doppelte Links werden nur einmal geliefert`() {
        val url = "https://rapidgator.net/file/abc/x.zip"
        val links = LinkParser.parse("$url\n$url")
        assertEquals(1, links.size)
    }

    @Test
    fun `unbekannte URLs ergeben leere Liste`() {
        assertTrue(LinkParser.parse("https://google.com https://example.org/file/abc").isEmpty())
    }

    @Test
    fun `Kommas trennen mehrere Links`() {
        val links = LinkParser.parse(
            "https://rapidgator.net/file/abc123/a.rar,https://1fichier.com/?abcde12345,https://ddl.to/a1b2c3d4e5f6"
        )
        assertEquals(
            listOf(
                "https://rapidgator.net/file/abc123/a.rar",
                "https://1fichier.com/?abcde12345",
                "https://ddl.to/a1b2c3d4e5f6"
            ),
            links.map { it.first }
        )
    }

    @Test
    fun `Anfuehrungszeichen und Klammern beenden den Link`() {
        val text = """
            <a href="https://rapidgator.net/file/abc123/a.rar">x</a>
            [https://1fichier.com/?abcde12345]
            'https://ddownload.com/a1b2c3d4e5f6'
            <https://rg.to/file/def456/b.rar>
        """.trimIndent()
        val links = LinkParser.parse(text)
        assertEquals(
            listOf(
                "https://rapidgator.net/file/abc123/a.rar",
                "https://1fichier.com/?abcde12345",
                "https://ddownload.com/a1b2c3d4e5f6",
                "https://rg.to/file/def456/b.rar"
            ),
            links.map { it.first }
        )
    }

    @Test
    fun `Alias-Domains von 1fichier werden erkannt`() {
        val links = LinkParser.parse("https://desfichiers.com/?abcde12345 und https://megadl.fr/?zyxwv98765")
        assertEquals(2, links.size)
        assertTrue(links.all { it.second.id == "onefichier" })
    }
}
