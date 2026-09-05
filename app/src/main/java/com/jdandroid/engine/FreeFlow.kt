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
 * Ablauf ohne Premium: Wahl zwischen Premium- und Free-Weg beim Aufloesen,
 * Wartezeiten des Hosters und Captcha-Halt. Der Eintrag bleibt dabei QUEUED
 * mit einem retryAt in der Zukunft; pump() der Engine ueberspringt ihn bis
 * dahin und stellt den Timer. Den prozessweiten Zustand (Captcha-Seite,
 * Hinweise aus dem Browser) verwaltet [FreeDownloads].
 */
internal class FreeFlow(
    private val dao: DownloadDao,
    private val accountDao: AccountDao,
    private val settings: SettingsRepository
) {
    /**
     * Link aufloesen: nur ein Premium-Konto nimmt den Premium-Weg, ein
     * gueltiges Free-Konto wuerde dort dauerhaft scheitern ("benoetigt
     * Premium"). Ohne Premium der Free-Modus mit Wartezeiten und ggf.
     * Captcha; die Hinweise aus der Captcha-Ansicht (Direktlink, Cookies)
     * gelten fuer genau diesen Versuch.
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
     * Der Hoster verlangt eine Wartezeit. Der Eintrag bleibt QUEUED mit
     * retryAt nach Ablauf (nextQueued prueft retryAt). Kein Fehlversuch -
     * Warten ist kein Fehler. Gespeichert wird der Code [FreeMode.WAIT_CODE]
     * samt Grund des Hosters ("Tageslimit erreicht"); die Anzeige baut daraus
     * Countdown und Text.
     */
    suspend fun scheduleWait(id: Long, seconds: Int, reason: String?) {
        val item = dao.byId(id) ?: return
        val retryAt = FreeMode.retryAt(System.currentTimeMillis(), seconds)
        dao.scheduleRetry(id, item.attempts, retryAt, FreeMode.waitNote(reason))
    }

    /**
     * Captcha noetig. Der Eintrag bleibt QUEUED, aber mit retryAt weit in der
     * Zukunft - erst "Captcha loesen" (Browser) gibt ihn wieder frei. Seite
     * und Session-Cookies merkt sich [FreeDownloads] prozessweit; gespeichert
     * wird der Code [FreeMode.CAPTCHA_CODE] samt Grund des Hosters (Passwort,
     * Turnstile).
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
