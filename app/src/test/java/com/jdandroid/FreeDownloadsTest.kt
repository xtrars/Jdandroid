package com.jdandroid

import com.jdandroid.engine.CaptchaPage
import com.jdandroid.engine.FreeDownloads
import com.jdandroid.hoster.FreeHints
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Browser hints are consumed by a single attempt; a held captcha page is gone once hints arrive or the entry is forgotten. */
class FreeDownloadsTest {

    // Process-wide state: leave nothing behind for other tests
    @After
    fun clear() {
        (1L..2L).forEach { FreeDownloads.forget(it) }
    }

    @Test
    fun `hinweise gelten nur fuer einen versuch und loeschen die captcha-seite`() {
        FreeDownloads.captchaRequired(1, CaptchaPage("https://x/captcha"))
        assertEquals("https://x/captcha", FreeDownloads.captchaPage(1)?.url)

        FreeDownloads.putHints(1, FreeHints(direktUrlAusBrowser = "https://a-3.1fichier.com/c1", cookies = "LG=en"))
        assertNull(FreeDownloads.captchaPage(1))
        assertEquals(FreeHints("https://a-3.1fichier.com/c1", "LG=en"), FreeDownloads.takeHints(1))
        assertNull(FreeDownloads.takeHints(1))
    }

    @Test
    fun `forget entfernt captcha-seite und hinweise`() {
        FreeDownloads.captchaRequired(2, CaptchaPage("https://x/captcha2"))
        FreeDownloads.putHints(2, FreeHints(direktUrlAusBrowser = "https://a-4.1fichier.com/c2"))
        FreeDownloads.captchaRequired(2, CaptchaPage("https://x/captcha3"))

        FreeDownloads.forget(2)
        assertNull(FreeDownloads.captchaPage(2))
        assertNull(FreeDownloads.takeHints(2))
    }
}
