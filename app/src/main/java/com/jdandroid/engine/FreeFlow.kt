package com.jdandroid.engine

import com.jdandroid.core.FreeMode
import com.jdandroid.data.AccountDao
import com.jdandroid.data.DownloadDao
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.SettingsRepository
import com.jdandroid.data.hasPremium
import com.jdandroid.hoster.CaptchaRequiredException
import com.jdandroid.hoster.FreeHints
import com.jdandroid.hoster.Hoster
import com.jdandroid.hoster.HosterException
import com.jdandroid.hoster.ResolvedLink

/**
 * Premium-or-free decision when resolving a link, plus hoster wait times and
 * captcha holds. A waiting entry stays QUEUED with a future retryAt; the
 * engine's pump() skips it until then and arms the timer. Process-wide state
 * (captcha page, browser hints) lives in [FreeDownloads].
 */
internal class FreeFlow(
    private val dao: DownloadDao,
    private val accountDao: AccountDao,
    private val settings: SettingsRepository
) {
    /**
     * Only a premium account takes the premium path; a valid free account
     * would fail there permanently. Browser hints (direct link, cookies)
     * apply to this one attempt.
     */
    suspend fun resolve(id: Long, item: DownloadItem, hoster: Hoster): ResolvedLink {
        val account = accountDao.validForHoster(item.hosterId)
        val premium = account?.takeIf { it.hasPremium() }
        return when {
            premium != null -> hoster.resolve(item.url, premium)
            settings.currentFreeMode() ->
                hoster.resolveFree(item.url, FreeDownloads.takeHints(id) ?: FreeHints())
            account != null -> throw HosterException(FreeMode.noPremiumMessage(), true)
            else -> throw HosterException(FreeMode.disabledMessage(), true)
        }
    }

    /**
     * Keeps the entry QUEUED with retryAt after the hoster's wait time; this
     * does not count as a failed attempt. The note stores [FreeMode.WAIT_CODE]
     * plus the hoster's reason, from which the UI builds countdown and text.
     */
    suspend fun scheduleWait(id: Long, seconds: Int, reason: String?) {
        val item = dao.byId(id) ?: return
        val retryAt = FreeMode.retryAt(System.currentTimeMillis(), seconds)
        dao.scheduleRetry(id, item.attempts, retryAt, FreeMode.waitNote(reason))
    }

    /**
     * Keeps the entry QUEUED with retryAt far in the future; only solving the
     * captcha in the browser releases it. Page and session cookies go to
     * [FreeDownloads], the note stores [FreeMode.CAPTCHA_CODE] plus the reason.
     */
    suspend fun holdForCaptcha(id: Long, e: CaptchaRequiredException) {
        val item = dao.byId(id) ?: return
        FreeDownloads.captchaRequired(id, CaptchaPage(e.pageUrl, e.cookieUrl, e.cookies))
        dao.scheduleRetry(
            id, item.attempts, System.currentTimeMillis() + FreeMode.CAPTCHA_HOLD_MS,
            FreeMode.captchaNote(e.message)
        )
    }
}
