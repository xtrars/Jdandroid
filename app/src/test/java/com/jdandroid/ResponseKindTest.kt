package com.jdandroid

import com.jdandroid.core.Texts
import com.jdandroid.core.formatBytes
import com.jdandroid.engine.ResponseKind
import com.jdandroid.engine.classifyResponse
import com.jdandroid.engine.transferTotal
import com.jdandroid.hoster.HosterException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseKindTest {

    @Test
    fun `416 mit passender Teildatei ist fertig`() {
        assertEquals(ResponseKind.AlreadyComplete, classifyResponse(416, null, null, 1000, 1000))
        // Unknown size: trust the partial file
        assertEquals(ResponseKind.AlreadyComplete, classifyResponse(416, null, null, 1000, -1))
    }

    @Test
    fun `416 mit abweichender Groesse ist Neustart`() {
        assertEquals(ResponseKind.RestartMismatch, classifyResponse(416, null, null, 1200, 1000))
    }

    @Test
    fun `416 ohne Teildatei ist ein HTTP-Fehler`() {
        assertEquals(ResponseKind.HttpError, classifyResponse(416, null, null, 0, 1000))
        assertEquals(ResponseKind.HttpError, classifyResponse(503, "application/octet-stream", null, 0, -1))
    }

    @Test
    fun `HTML ohne attachment wird nie gespeichert`() {
        assertEquals(ResponseKind.HtmlPage, classifyResponse(200, "text/html; charset=utf-8", null, 0, -1))
        assertEquals(ResponseKind.HtmlPage, classifyResponse(200, "Text/HTML", "inline", 0, -1))
        assertEquals(ResponseKind.HtmlPage, classifyResponse(200, "application/xhtml+xml", null, 0, -1))
    }

    @Test
    fun `HTML mit attachment ist eine Datei`() {
        assertEquals(ResponseKind.Continue, classifyResponse(200, "text/html", "Attachment; filename=\"a.html\"", 0, -1))
    }

    @Test
    fun `200 nach Range-Anfrage heisst von vorn`() {
        assertEquals(ResponseKind.RangeIgnored, classifyResponse(200, "application/octet-stream", null, 500, 1000))
        assertEquals(ResponseKind.Continue, classifyResponse(206, "application/octet-stream", null, 500, 1000))
        assertEquals(ResponseKind.Continue, classifyResponse(200, "application/octet-stream", null, 0, 1000))
    }

    @Test
    fun `Gesamtgroesse aus Content-Length plus Offset, sonst bekannte Groesse`() {
        assertEquals(1000L, transferTotal(1000, 0, -1))
        assertEquals(1000L, transferTotal(600, 400, 1000))
        assertEquals(1000L, transferTotal(-1, 400, 1000))
        assertEquals(-1L, transferTotal(-1, 0, -1))
        // A rounded page size (1000- instead of 1024-based) is no contradiction
        assertEquals(1_380_000_000L, transferTotal(1_380_000_000L, 0, 1_481_763_717L))
    }

    @Test
    fun `leere Antwort wird nie als Datei gespeichert`() {
        val e = assertThrows(HosterException::class.java) { transferTotal(0, 0, 1000) }
        assertEquals(Texts.t("engine_empty_response"), e.message)
        assertFalse(e.permanent)
        assertThrows(HosterException::class.java) { transferTotal(0, 0, -1) }
        assertThrows(HosterException::class.java) { transferTotal(0, 500, 1000) }
    }

    @Test
    fun `Laenge im Widerspruch zur bekannten Groesse wird abgelehnt`() {
        // Short error text instead of the file
        val known = 700L * 1024 * 1024
        val e = assertThrows(HosterException::class.java) { transferTotal(48, 0, known) }
        assertFalse(e.permanent)
        assertEquals(Texts.t("engine_size_mismatch", formatBytes(48), formatBytes(known)), e.message)
        // A different, much larger file
        assertThrows(HosterException::class.java) { transferTotal(5000, 0, 1000) }
        // The resume offset counts
        assertThrows(HosterException::class.java) { transferTotal(10, 400, 5000) }
    }
}
