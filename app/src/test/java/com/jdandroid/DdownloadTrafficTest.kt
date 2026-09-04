package com.jdandroid

import com.jdandroid.hoster.DdownloadHoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kontingent-Erkennung auf der ddownload-Kontoseite in verschiedenen Layouts. */
class DdownloadTrafficTest {

    private val ddl = DdownloadHoster()
    private val gb = 1L shl 30

    @Test
    fun klassischesLayoutMitDoppelpunkt() {
        val html = """<table><tr><td>Traffic available:</td><td><b>120.5 GB</b></td></tr></table>"""
        val t = ddl.parseTraffic(html)
        assertEquals((120.5 * gb).toLong(), t.left)
        assertFalse(t.unlimited)
    }

    @Test
    fun neuesLayoutMitGesamt() {
        val html = """<div class="dk-stat"><span class="dk-label">Premium traffic left</span>
            <span class="dk-value">45,2 GB / 200 GB</span></div>"""
        val t = ddl.parseTraffic(html)
        assertEquals((45.2 * gb).toLong(), t.left)
        assertEquals(200 * gb, t.total)
    }

    @Test
    fun zahlVorDemWort() {
        val html = """<p>You have <strong>87 GB</strong> of traffic left today.</p>"""
        val t = ddl.parseTraffic(html)
        assertEquals(87 * gb, t.left)
    }

    @Test
    fun unbegrenzt() {
        val html = """<li>Traffic available: <b>Unlimited</b></li>"""
        val t = ddl.parseTraffic(html)
        assertTrue(t.unlimited)
        assertEquals(-1L, t.left)
    }

    @Test
    fun nichtLesbarLiefertDiagnoseAusschnitt() {
        val html = """<html><body><h1>My account</h1><div>Account type: Premium</div></body></html>"""
        val t = ddl.parseTraffic(html)
        assertEquals(-1L, t.left)
        assertTrue(t.snippet.contains("My account"))
    }

    @Test
    fun scriptInhaltWirdIgnoriert() {
        val html = """<script>var traffic = "999 GB";</script><div>Traffic available: 10 GB</div>"""
        assertEquals(10 * gb, ddl.parseTraffic(html).left)
    }

    @Test
    fun apiKeyNurMitKlarerForm() {
        assertEquals(
            "abcdef0123456789abcd",
            ddl.apiKeyFromPage("""<label>API Key</label><input type="text" value="abcdef0123456789abcd">""")
        )
        assertNull(ddl.apiKeyFromPage("""<div>API documentation</div><input value="short">"""))
    }
}

class DdownloadQuotaUnitTest {
    private val ddl = DdownloadHoster()

    @Test
    fun kilobyteWerdenKorrektUmgerechnet() {
        // 193 GiB in KB (so liefert es die API): 193 * 1024 * 1024
        val raw = 193.0 * 1024 * 1024
        assertEquals(193L shl 30, ddl.quotaToBytes(raw))
    }

    @Test
    fun byteWerteBleibenByte() {
        // Waere der Wert schon in Byte (193 GiB), ergaebe KB 193 TiB -> unplausibel
        val raw = (193L shl 30).toDouble()
        assertEquals(193L shl 30, ddl.quotaToBytes(raw))
    }
}

class DdownloadPlausibilityTest {
    private val ddl = DdownloadHoster()

    @Test
    fun tebibyteWerdenAufGibibyteZurueckgefuehrt() {
        val wrong = (193.9 * (1L shl 40)).toLong()
        val fixed = ddl.plausibleQuota(wrong)
        assertTrue("$fixed", fixed in (193L shl 30)..(195L shl 30))
    }

    @Test
    fun plausibleWerteBleibenUnveraendert() {
        assertEquals(150L shl 30, ddl.plausibleQuota(150L shl 30))
        assertEquals(0L, ddl.plausibleQuota(0L))
    }
}
