package com.jdandroid

import com.jdandroid.data.Account
import com.jdandroid.core.Texts
import com.jdandroid.hoster.AccountInfo
import com.jdandroid.hoster.AccountType
import com.jdandroid.hoster.DirectLinks
import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.Hoster
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.HosterRegistry
import com.jdandroid.hoster.ResolvedLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Standardverhalten der Free-Schnittstelle: [Hoster.isDirectDownloadUrl]
 * ohne eigene Umsetzung, [Hoster.resolveFree] ohne Unterstuetzung.
 */
class DirectLinksTest {

    /** Minimaler Hoster, der nur die Pflichtteile der Schnittstelle liefert. */
    private val hoster = object : Hoster {
        override val id = "test"
        override val displayName = "Test"
        override val accountType = AccountType.API_KEY
        override val accountHint = ""
        override val siteHosts = setOf("example.com", "www.example.com")
        override fun matches(url: String) = url.contains("example.com/")
        override suspend fun checkAccount(account: Account) = AccountInfo(valid = false, statusText = "")
        override suspend fun resolve(url: String, account: Account?) = ResolvedLink(url)
    }

    @Test
    fun fileserverAufAndererSubdomainMitDateiendung() {
        assertTrue(hoster.isDirectDownloadUrl("https://s12.example.com/d/abc/name.part1.rar"))
        assertTrue(hoster.isDirectDownloadUrl("https://fs07.example.com:183/d/abc/name.mkv"))
        assertTrue(hoster.isDirectDownloadUrl("https://cdn7.other-cdn.net/d/abc/name.mkv?token=1"))
        assertTrue(hoster.isDirectDownloadUrl("https://s1.example.com/cgi-bin/dl.cgi/abc/name.rar"))
    }

    @Test
    fun hauptdomainIstNieDirektlink() {
        assertFalse(hoster.isDirectDownloadUrl("https://example.com/abc/name.rar"))
        assertFalse(hoster.isDirectDownloadUrl("https://www.example.com/name.zip"))
        assertFalse(hoster.isDirectDownloadUrl("https://EXAMPLE.com/name.zip"))
    }

    @Test
    fun seitenSkripteUndCgiFallenHeraus() {
        assertFalse(hoster.isDirectDownloadUrl("https://s12.example.com/login.html"))
        assertFalse(hoster.isDirectDownloadUrl("https://s12.example.com/cgi-bin/tracker.cgi?file_code=x"))
        assertFalse(hoster.isDirectDownloadUrl("https://cdn.example.com/assets/style.css"))
        assertFalse(hoster.isDirectDownloadUrl("https://www.google.com/recaptcha/api.js"))
        assertFalse(hoster.isDirectDownloadUrl("https://s12.example.com/abc123"))
        assertFalse(hoster.isDirectDownloadUrl("/relative/name.rar"))
        assertFalse(hoster.isDirectDownloadUrl(""))
    }

    @Test
    fun alleRegistriertenHosterNennenIhreDomains() {
        HosterRegistry.hosters.forEach { h ->
            assertTrue(h.id, h.siteHosts.isNotEmpty())
            assertTrue(h.id, h.siteHosts.all { it == it.lowercase() })
        }
        assertFalse(DirectLinks.isDirectDownloadUrl("https://rapidgator.net/file/abc/name.rar.html", HosterRegistry.byId("rapidgator")!!.siteHosts))
    }

    @Test
    fun siteHostErkenntHauptdomainUndSubdomains() {
        val hosts = hoster.siteHosts
        assertTrue(DirectLinks.isSiteHost("https://example.com/abc", hosts))
        assertTrue(DirectLinks.isSiteHost("https://S12.Example.com:183/d/abc/name.rar", hosts))
        assertFalse(DirectLinks.isSiteHost("https://cdn7.other-cdn.net/d/abc/name.mkv", hosts))
        assertFalse(DirectLinks.isSiteHost("https://example.com.evil.org/name.rar", hosts))
        assertFalse(DirectLinks.isSiteHost("https://notexample.com/name.rar", hosts))
        assertFalse(DirectLinks.isSiteHost("", hosts))
    }

    @Test
    fun resolveFreeOhneUmsetzungIstPermanent() {
        val error = runCatching { runBlocking { hoster.resolveFree("https://example.com/abc", FreeHints()) } }
            .exceptionOrNull() as HosterException
        assertTrue(error.permanent)
        assertEquals(Texts.t("hoster_free_unsupported"), error.message)
        assertFalse(hoster.supportsFree)
        assertEquals(Texts.t("hoster_free_status_unsupported"), hoster.freeStatusText)
    }
}
