package com.jdandroid

import com.jdandroid.hoster.DdownloadAccountPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/** Erwartete Epoche (Standardzeitzone, wie SimpleDateFormat sie im Parser verwendet). */
private fun epoch(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long =
    GregorianCalendar(year, month, day, hour, minute, second).timeInMillis

/** Kontingent-Erkennung auf der ddownload-Kontoseite in verschiedenen Layouts. */
class DdownloadTrafficTest {

    private val gb = 1L shl 30

    @Test
    fun klassischesLayoutMitDoppelpunkt() {
        val html = """<table><tr><td>Traffic available:</td><td><b>120.5 GB</b></td></tr></table>"""
        val t = DdownloadAccountPage.parseTraffic(html)
        assertEquals((120.5 * gb).toLong(), t.left)
        assertFalse(t.unlimited)
    }

    @Test
    fun neuesLayoutMitGesamt() {
        val html = """<div class="dk-stat"><span class="dk-label">Premium traffic left</span>
            <span class="dk-value">45,2 GB / 200 GB</span></div>"""
        val t = DdownloadAccountPage.parseTraffic(html)
        assertEquals((45.2 * gb).toLong(), t.left)
        assertEquals(200 * gb, t.total)
    }

    @Test
    fun zahlVorDemWort() {
        val html = """<p>You have <strong>87 GB</strong> of traffic left today.</p>"""
        val t = DdownloadAccountPage.parseTraffic(html)
        assertEquals(87 * gb, t.left)
    }

    @Test
    fun unbegrenzt() {
        val html = """<li>Traffic available: <b>Unlimited</b></li>"""
        val t = DdownloadAccountPage.parseTraffic(html)
        assertTrue(t.unlimited)
        assertEquals(-1L, t.left)
    }

    @Test
    fun nichtLesbarLiefertMinusEins() {
        val html = """<html><body><h1>My account</h1><div>Account type: Premium</div></body></html>"""
        val t = DdownloadAccountPage.parseTraffic(html)
        assertEquals(-1L, t.left)
        assertEquals(-1L, t.total)
        assertFalse(t.unlimited)
    }

    @Test
    fun kaufangebotGiltNichtAlsRestmenge() {
        val free = """<div>Account-Status Free</div><a>Traffic kaufen</a><div>200 GB + Daten &euro;15.99</div>"""
        assertEquals(-1L, DdownloadAccountPage.parseTraffic(free).left)
        val premium = """<div>Premium: 200 GB traffic per day</div><div>Verfügbare Daten</div><div>45 GB</div>"""
        assertEquals(45 * gb, DdownloadAccountPage.parseTraffic(premium).left)
    }

    @Test
    fun deutschesDatumWirdGelesen() {
        val exp = epoch(2030, Calendar.DECEMBER, 2)
        assertEquals(exp, DdownloadAccountPage.parseExpire("2 Dezember 2030"))
        assertEquals(exp, DdownloadAccountPage.pageExpire("Aktiv bis 2 Dezember 2030"))
    }

    @Test
    fun scriptInhaltWirdIgnoriert() {
        val html = """<script>var traffic = "999 GB";</script><div>Traffic available: 10 GB</div>"""
        assertEquals(10 * gb, DdownloadAccountPage.parseTraffic(html).left)
    }

    @Test
    fun apiKeyNurMitKlarerForm() {
        assertEquals(
            "abcdef0123456789abcd",
            DdownloadAccountPage.apiKeyFromPage("""<label>API Key</label><input type="text" value="abcdef0123456789abcd">""")
        )
        assertNull(DdownloadAccountPage.apiKeyFromPage("""<div>API documentation</div><input value="short">"""))
    }
}

class DdownloadQuotaUnitTest {

    @Test
    fun megabyteLautApiDoku() {
        // Doku: 102400 = 100 GB
        assertEquals(100L shl 30, DdownloadAccountPage.quotaToBytes(102400.0))
    }

    @Test
    fun apiWertLandetImTageskontingent() {
        // 197040 MB laut API = 192,4 GiB; passt zu 200 GB pro Tag
        assertEquals(197040L shl 20, DdownloadAccountPage.plausibleQuota(DdownloadAccountPage.quotaToBytes(197040.0)))
    }
}

class DdownloadPlausibilityTest {

    @Test
    fun falschBeschriftetesGbWirdZuMb() {
        // Kontoseite: "197040 GB" bei 200 GB Tageskontingent -> gemeint sind MB
        assertEquals(197040L shl 20, DdownloadAccountPage.plausibleQuota(197040L shl 30))
    }

    @Test
    fun dazugekaufterTrafficBleibtErhalten() {
        assertEquals(1200L shl 30, DdownloadAccountPage.plausibleQuota(1200L shl 30))
        assertEquals(150L shl 30, DdownloadAccountPage.plausibleQuota(150L shl 30))
        assertEquals(0L, DdownloadAccountPage.plausibleQuota(0L))
    }
}

class DdownloadUltimatePageTest {
    private val gb = 1L shl 30

    private val html = """<div>Mein Konto - DDownload</div><div>Ultimate Premium account</div>
        <div>Unbegrenzter Speicher</div><div>Account-Status</div><div>Ultimate</div>
        <div>Aktiv bis 2 December 2026</div><div>Verfügbare Daten</div><div>197040 GB</div>
        <a>Traffic kaufen</a><div>200 GB + Daten &euro;15.99</div>"""

    @Test
    fun verfuegbareDatenWerdenGelesen() {
        val t = DdownloadAccountPage.parseTraffic(html)
        assertEquals(197040 * gb, t.left)
        assertFalse(t.unlimited)
        // Die Seite beschriftet falsch: gemeint sind 192,4 GiB
        assertEquals(197040L shl 20, DdownloadAccountPage.plausibleQuota(t.left))
    }

    @Test
    fun aktivBisWirdAlsAblaufdatumGelesen() {
        val expire = DdownloadAccountPage.pageExpire("Account-Status Ultimate Aktiv bis 2 December 2026 Verfügbare Daten")
        assertEquals(epoch(2026, Calendar.DECEMBER, 2), expire)
        assertEquals(0L, DdownloadAccountPage.pageExpire("Kein Datum hier"))
        assertEquals(epoch(2030, Calendar.JANUARY, 5), DdownloadAccountPage.pageExpire("Premium expire: 05 January 2030"))
    }
}

class DdownloadExpireTest {

    @Test
    fun verschiedeneDatumsformate() {
        val jan5 = epoch(2030, Calendar.JANUARY, 5)
        assertEquals(epoch(2030, Calendar.JANUARY, 5, 12), DdownloadAccountPage.parseExpire("2030-01-05 12:00:00"))
        assertEquals(jan5, DdownloadAccountPage.parseExpire("2030-01-05"))
        assertEquals(jan5, DdownloadAccountPage.parseExpire("05 January 2030"))
        assertEquals(1893456000000L, DdownloadAccountPage.parseExpire("1893456000"))
        assertEquals(1893456000000L, DdownloadAccountPage.parseExpire("1893456000000"))
        assertEquals(0L, DdownloadAccountPage.parseExpire(""))
        assertEquals(0L, DdownloadAccountPage.parseExpire("null"))
        assertEquals(0L, DdownloadAccountPage.parseExpire("irgendwann"))
    }
}
