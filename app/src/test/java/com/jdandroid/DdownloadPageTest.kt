package com.jdandroid

import com.jdandroid.hoster.DdownloadPage
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.PageResponse
import com.jdandroid.hoster.WaitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Page checks of ddownload.com that premium and free flow share. */
class DdownloadPageTest {

    private fun waitOf(resp: PageResponse): Int? = try {
        DdownloadPage.checkBlocked(resp)
        null
    } catch (e: WaitException) {
        e.seconds
    }

    @Test
    fun wartungUndCloudflareWerdenAbgewartet() {
        assertEquals(DdownloadPage.MAINTENANCE_WAIT_SECONDS, waitOf(PageResponse(503, "")))
        assertEquals(DdownloadPage.BLOCK_WAIT_SECONDS, waitOf(PageResponse(403, "")))
        assertEquals(DdownloadPage.BLOCK_WAIT_SECONDS, waitOf(PageResponse(200, "<title>Just a moment...</title>")))
        assertEquals(null, waitOf(PageResponse(200, "<h1 class=\"dk-dl-name\">x.rar</h1>")))
    }

    private val loginForm = """
        <form method="POST" action="https://ddownload.com/" name="FL">
        <input type="hidden" name="op" value="login">
        <input type="text" name="login" value=""><input type="password" name="password">
        </form>
    """

    @Test
    fun abgemeldetLandetAufDemLoginFormular() {
        // Logged out, every account address redirects to the login page
        assertFalse(DdownloadPage.isLoggedIn(PageResponse(200, loginForm, finalUrl = "https://ddownload.com/login.html")))
        assertFalse(DdownloadPage.isLoggedIn(PageResponse(200, "", finalUrl = "https://ddownload.com/?op=login&redirect=x")))
        // The form inline on the account address counts the same
        assertFalse(DdownloadPage.isLoggedIn(PageResponse(200, loginForm, finalUrl = "https://ddownload.com/account")))
    }

    @Test
    fun kontoseiteOhneAlteMerkmaleGiltAlsAngemeldet() {
        // Current layout: /account without "op=logout" or "Account type"
        val neu = """<nav><a href="/my-files">My Files</a><a href="/account">Account</a></nav>
            <form><input type="password" name="password_new"></form><div>Premium until 2030-12-02</div>"""
        assertTrue(DdownloadPage.isLoggedIn(PageResponse(200, neu, finalUrl = "https://ddownload.com/account")))
        assertTrue(
            DdownloadPage.isLoggedIn(
                PageResponse(200, """<a href="/?op=logout">Logout</a>""", finalUrl = "https://ddownload.com/?op=my_account")
            )
        )
    }

    @Test
    fun dateicodeNurMitZwoelfZeichen() {
        assertEquals("chnaz5epeg4t", DdownloadPage.fileCode("https://ddownload.com/chnaz5epeg4t/x.rar.html"))
        assertEquals("chnaz5epeg4t", DdownloadPage.fileCode("https://ddl.to/f/chnaz5epeg4t"))
        assertFalse(DdownloadPage.matches("https://ddownload.com/register.html"))
        try {
            DdownloadPage.fileCode("https://ddownload.com/register.html")
            fail("HosterException erwartet")
        } catch (e: HosterException) {
            assertTrue(e.permanent)
        }
    }

    @Test
    fun geloeschteDateiWirdErkannt() {
        assertTrue(DdownloadPage.isDeleted("<h2>File Not Found</h2>"))
        assertTrue(DdownloadPage.isDeleted("The file was deleted by its owner"))
        assertFalse(DdownloadPage.isDeleted("<h1 class=\"dk-dl-name\">x.rar</h1>"))
    }
}
