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
    fun megabyteLautApiDoku() {
        // Doku: 102400 = 100 GB
        assertEquals(100L shl 30, ddl.quotaToBytes(102400.0))
    }

    @Test
    fun apiWertLandetImTageskontingent() {
        // 197040 MB laut API = 192,4 GiB; passt zu 200 GB pro Tag
        assertEquals(197040L shl 20, ddl.plausibleQuota(ddl.quotaToBytes(197040.0)))
    }
}

class DdownloadPlausibilityTest {
    private val ddl = DdownloadHoster()

    @Test
    fun falschBeschriftetesGbWirdZuMb() {
        // Kontoseite: "197040 GB" bei 200 GB Tageskontingent -> gemeint sind MB
        assertEquals(197040L shl 20, ddl.plausibleQuota(197040L shl 30))
    }

    @Test
    fun dazugekaufterTrafficBleibtErhalten() {
        assertEquals(1200L shl 30, ddl.plausibleQuota(1200L shl 30))
        assertEquals(150L shl 30, ddl.plausibleQuota(150L shl 30))
        assertEquals(0L, ddl.plausibleQuota(0L))
    }
}

class DdownloadUltimatePageTest {
    private val ddl = DdownloadHoster()
    private val gb = 1L shl 30

    private val html = """<div>Mein Konto - DDownload</div><div>Ultimate Premium account</div>
        <div>Unbegrenzter Speicher</div><div>Account-Status</div><div>Ultimate</div>
        <div>Aktiv bis 2 December 2026</div><div>Verfügbare Daten</div><div>197040 GB</div>
        <a>Traffic kaufen</a><div>200 GB + Daten &euro;15.99</div>"""

    @Test
    fun verfuegbareDatenWerdenGelesen() {
        val t = ddl.parseTraffic(html)
        assertEquals(197040 * gb, t.left)
        assertFalse(t.unlimited)
        // Die Seite beschriftet falsch: gemeint sind 192,4 GiB
        assertEquals(197040L shl 20, ddl.plausibleQuota(t.left))
    }

    @Test
    fun aktivBisWirdAlsAblaufdatumGelesen() {
        val expire = ddl.pageExpire("Account-Status Ultimate Aktiv bis 2 December 2026 Verfügbare Daten")
        assertTrue(expire > 0)
        assertEquals(0L, ddl.pageExpire("Kein Datum hier"))
        assertTrue(ddl.pageExpire("Premium expire: 05 January 2030") > 0)
    }
}

class DdownloadExpireTest {
    private val ddl = DdownloadHoster()

    @Test
    fun verschiedeneDatumsformate() {
        assertTrue(ddl.parseExpire("2030-01-05 12:00:00") > 0)
        assertTrue(ddl.parseExpire("2030-01-05") > 0)
        assertTrue(ddl.parseExpire("05 January 2030") > 0)
        assertTrue(ddl.parseExpire("1893456000") > 0)
        assertEquals(0L, ddl.parseExpire(""))
        assertEquals(0L, ddl.parseExpire("null"))
        assertEquals(0L, ddl.parseExpire("irgendwann"))
    }
}
