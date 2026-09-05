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
 * Host filter of the captcha view: hoster domains, the page host and the
 * captcha services of Cloudflare, Google and hCaptcha. Everything else is
 * answered with an empty response.
 */
internal fun isCaptchaHostAllowed(host: String?, siteHosts: Set<String>, pageHost: String?): Boolean {
    val h = host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    if (isHosterHost(h, siteHosts, pageHost)) return true
    return h == "challenges.cloudflare.com" || h.endsWith(".cloudflare.com") ||
        h == "www.google.com" || h == "www.gstatic.com" ||
        h == "recaptcha.net" || h.endsWith(".recaptcha.net") ||
        h == "hcaptcha.com" || h.endsWith(".hcaptcha.com")
}

/** Hoster domains including subdomains (file servers) and the page host itself. */
internal fun isHosterHost(host: String?, siteHosts: Set<String>, pageHost: String?): Boolean {
    val h = host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    val own = siteHosts.map { it.lowercase() } + listOfNotNull(pageHost?.lowercase()?.takeIf { it.isNotBlank() })
    return own.any { h == it || h.endsWith(".$it") }
}

internal enum class CaptchaRequestAction { CAPTURE, LOAD, BLOCK }

/**
 * Decides per request. Only a main-frame navigation on a hoster host counts as
 * a direct link: sub-resources are never captured, or an ad or captcha script
 * loading a file with a matching extension would close the view before the
 * user saw the captcha; and the captured link is fetched with the hoster's
 * cookies, which must not reach a foreign host.
 */
internal fun captchaRequestAction(
    isMainFrame: Boolean,
    isDirectLink: Boolean,
    hosterHost: Boolean,
    hostAllowed: Boolean
): CaptchaRequestAction = when {
    isMainFrame && isDirectLink && hosterHost -> CaptchaRequestAction.CAPTURE
    hostAllowed -> CaptchaRequestAction.LOAD
    else -> CaptchaRequestAction.BLOCK
}

/**
 * Solves a captcha in an embedded browser (free mode). The user works the
 * hoster page through to the download button; the navigation to the file
 * server ([isDirectDownloadUrl]) is intercepted, not loaded, and handed to
 * the engine via [onDirectLink] with the cookies. The browser itself never
 * downloads a file.
 *
 * The hoster's session [cookies] (e.g. the Rapidgator timer) are set right
 * before loading, so no other browser view clears them first and two waiting
 * entries of the same hoster do not interfere.
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
    // Redirect and DownloadListener can report the same URL more than once.
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
    fun hosterHost(host: String?): Boolean = isHosterHost(host, siteHosts, pageHost)

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

    /** For calls from the shouldInterceptRequest background thread. */
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
                        // Any browser download (Content-Disposition, unknown type)
                        // becomes the direct link instead.
                        setDownloadListener { url, _, _, _, _ ->
                            if (hosterHost(url.toUri().host)) capture(url)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                val host = request.url?.host
                                val action = captchaRequestAction(
                                    true, isDirectDownloadUrl(url), hosterHost(host), allowed(host)
                                )
                                return when (action) {
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

                            // Background thread; a redirect after a POST arrives
                            // here as main frame, not in shouldOverrideUrlLoading.
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString() ?: return empty()
                                val host = request.url?.host
                                val action = captchaRequestAction(
                                    request.isForMainFrame, isDirectDownloadUrl(url), hosterHost(host), allowed(host)
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
