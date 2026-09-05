package com.jdandroid.engine

import com.jdandroid.hoster.FreeHints
import java.util.concurrent.ConcurrentHashMap

/** Captcha page of a held entry plus the hoster's session cookies (Set-Cookie lines for [cookieUrl]). */
data class CaptchaPage(
    val url: String,
    val cookieUrl: String? = null,
    val cookies: List<String> = emptyList()
)

/**
 * Process-wide free-mode state per entry: the captcha page a download is held
 * on and the hints (direct link, cookies) taken over from the browser for the
 * next attempt. Kept out of the database on purpose: a direct link is valid
 * for minutes only, and after a process restart the captcha view falls back
 * to the link URL.
 */
object FreeDownloads {
    private val captchaPages = ConcurrentHashMap<Long, CaptchaPage>()
    private val hints = ConcurrentHashMap<Long, FreeHints>()

    fun captchaRequired(id: Long, page: CaptchaPage) { captchaPages[id] = page }

    fun captchaPage(id: Long): CaptchaPage? = captchaPages[id]

    fun putHints(id: Long, value: FreeHints) {
        hints[id] = value
        captchaPages.remove(id)
    }

    /** Removes and returns the hints; a direct link is good for one attempt only. */
    fun takeHints(id: Long): FreeHints? = hints.remove(id)

    fun forget(id: Long) {
        captchaPages.remove(id)
        hints.remove(id)
    }
}
