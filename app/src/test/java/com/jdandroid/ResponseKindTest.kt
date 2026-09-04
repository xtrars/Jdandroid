package com.jdandroid

import com.jdandroid.engine.ResponseKind
import com.jdandroid.engine.classifyResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseKindTest {

    @Test
    fun `416 mit passender Teildatei ist fertig`() {
        assertEquals(ResponseKind.AlreadyComplete, classifyResponse(416, null, null, 1000, 1000))
        // Groesse unbekannt: der Teildatei vertrauen
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
}
