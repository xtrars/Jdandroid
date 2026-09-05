package com.jdandroid.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.jdandroid.R
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-Filter der Captcha-Ansicht: die Hoster-Domains (inklusive Subdomains),
 * die Seite selbst sowie die Captcha-Dienste von Cloudflare, Google
 * (reCAPTCHA) und hCaptcha. Alles andere wird leer beantwortet.
 */
internal fun isCaptchaHostAllowed(host: String?, siteHosts: Set<String>, pageHost: String?): Boolean {
    val h = host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    val own = siteHosts.map { it.lowercase() } + listOfNotNull(pageHost?.lowercase()?.takeIf { it.isNotBlank() })
    if (own.any { h == it || h.endsWith(".$it") }) return true
    return h == "challenges.cloudflare.com" || h.endsWith(".cloudflare.com") ||
        h == "www.google.com" || h == "www.gstatic.com" ||
        h == "recaptcha.net" || h.endsWith(".recaptcha.net") ||
        h == "hcaptcha.com" || h.endsWith(".hcaptcha.com")
}

/** Was die Captcha-Ansicht mit einer Anfrage der WebView tut. */
internal enum class CaptchaRequestAction { CAPTURE, LOAD, BLOCK }

/**
 * Entscheidung je Anfrage: nur eine Navigation des Hauptrahmens (Formular-
 * Weiterleitung, Klick auf den Download-Knopf) darf als Direktlink gelten.
 * Unterressourcen (Bilder, Skripte, XHR, iframes) werden nie abgefangen -
 * sonst schloesse ein Werbe- oder Captcha-Skript, das eine Datei mit
 * passender Endung laedt, die Ansicht, bevor der Nutzer ein Captcha sah -
 * sondern nur gegen den Host-Filter geprueft.
 */
internal fun captchaRequestAction(
    isMainFrame: Boolean,
    isDirectLink: Boolean,
    hostAllowed: Boolean
): CaptchaRequestAction = when {
    isMainFrame && isDirectLink -> CaptchaRequestAction.CAPTURE
    hostAllowed -> CaptchaRequestAction.LOAD
    else -> CaptchaRequestAction.BLOCK
}

/**
 * Captcha im eingebetteten Browser loesen (Free-Modus). Der Nutzer arbeitet
 * die Hoster-Seite bis zum Download-Knopf durch; die Navigation auf den
 * Fileserver ([isDirectDownloadUrl]) wird abgefangen, nicht geladen:
 * Adresse und Cookies gehen ueber [onDirectLink] an die Engine, die den
 * Eintrag sofort neu startet. Ein Download im Browser selbst findet nie
 * statt (Navigation, Subressource und DownloadListener werden abgefangen).
 *
 * Aufbau wie [WebLoginScreen]: eigener Bildschirm (Insets), JavaScript an
 * (Captcha-Dienste brauchen es), nur Hoster- und Captcha-Domains, Cookies
 * und WebStorage werden nach Uebernahme wie nach Abbruch geloescht. Die
 * Session-Cookies des Hoster-Ablaufs ([cookies] fuer [cookieUrl], z.B.
 * Rapidgator-Timer) werden erst hier, unmittelbar vor dem Laden, gesetzt:
 * so loescht keine andere Browser-Ansicht sie vorher, und zwei wartende
 * Eintraege desselben Hosters kommen sich nicht in die Quere.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptchaScreen(
    pageUrl: String,
    hosterName: String,
    cookieUrl: String? = null,
    cookies: List<String> = emptyList(),
    siteHosts: Set<String>,
    isDirectDownloadUrl: (String) -> Boolean,
    onCancel: () -> Unit,
    onDirectLink: (url: String, cookies: String?) -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    val captchaHint = stringResource(R.string.linkgrabber_captcha_hint)
    var status by remember { mutableStateOf(captchaHint) }
    val pageHost = remember(pageUrl) { pageUrl.toUri().host }
    // Nur einmal uebernehmen: Weiterleitung und DownloadListener koennen
    // dieselbe Adresse mehrfach melden
    val captured = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun clearWebSession() {
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { WebStorage.getInstance().deleteAllData() }
    }

    fun cancel() {
        clearWebSession()
        onCancel()
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else cancel()
    }

    fun allowed(host: String?): Boolean = isCaptchaHostAllowed(host, siteHosts, pageHost)

    /** Direktlink samt Cookies der Hoster-Seite (und des Fileservers) uebergeben. */
    fun capture(url: String) {
        if (!captured.compareAndSet(false, true)) return
        val manager = CookieManager.getInstance()
        val cookies = listOf(pageUrl, url)
            .mapNotNull { runCatching { manager.getCookie(it) }.getOrNull()?.takeIf { c -> c.isNotBlank() } }
            .distinct()
            .joinToString("; ")
            .ifBlank { null }
        onDirectLink(url, cookies)
        clearWebSession()
    }

    /** Aus einem Hintergrund-Thread (shouldInterceptRequest) auf den Hauptthread. */
    fun captureLater(url: String) {
        if (captured.get()) return
        mainHandler.post { capture(url) }
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linkgrabber_captcha_title, hosterName)) },
                navigationIcon = {
                    IconButton(onClick = { cancel() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                status,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        // Der Browser laedt nie selbst eine Datei: jeder Download
                        // (Content-Disposition, unbekannter Typ) wird als
                        // Direktlink uebernommen
                        setDownloadListener { url, _, _, _, _ -> capture(url) }
                        webViewClient = object : WebViewClient() {
                            // Nur Navigationen des Hauptrahmens
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                val host = request.url?.host
                                return when (captchaRequestAction(true, isDirectDownloadUrl(url), allowed(host))) {
                                    CaptchaRequestAction.CAPTURE -> { capture(url); true }
                                    CaptchaRequestAction.LOAD -> false
                                    CaptchaRequestAction.BLOCK -> {
                                        status = context.getString(
                                            R.string.linkgrabber_captcha_blocked,
                                            host ?: context.getString(R.string.linkgrabber_unknown_address),
                                            hosterName
                                        )
                                        true
                                    }
                                }
                            }

                            // Laeuft auf einem Hintergrund-Thread: auch eine
                            // Weiterleitung nach einem Formular (POST) landet hier -
                            // als Hauptrahmen. Unterressourcen nur filtern, nie abfangen
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString() ?: return empty()
                                val action = captchaRequestAction(
                                    request.isForMainFrame, isDirectDownloadUrl(url), allowed(request.url?.host)
                                )
                                return when (action) {
                                    CaptchaRequestAction.CAPTURE -> { captureLater(url); empty() }
                                    CaptchaRequestAction.LOAD -> null
                                    CaptchaRequestAction.BLOCK -> empty()
                                }
                            }

                            private fun empty() = WebResourceResponse(
                                "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        // Cookies des Hoster-Ablaufs erst jetzt in den Browser
                        if (cookies.isNotEmpty()) {
                            runCatching {
                                val manager = CookieManager.getInstance()
                                cookies.forEach { manager.setCookie(cookieUrl ?: pageUrl, it) }
                                manager.flush()
                            }
                        }
                        loadUrl(pageUrl)
                        webView = this
                    }
                },
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.stopLoading()
                    view.destroy()
                }
            )
        }
    }
}
