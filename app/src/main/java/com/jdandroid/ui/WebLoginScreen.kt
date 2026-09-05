package com.jdandroid.ui

import android.annotation.SuppressLint
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
import androidx.compose.material3.TextButton
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

/**
 * Host filter of the login browser: the login domain with subdomains and the
 * Cloudflare challenge, for navigations and sub-resources alike.
 */
internal fun isWebLoginHostAllowed(host: String?, loginHost: String): Boolean {
    val h = host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    val l = loginHost.lowercase()
    if (l.isBlank()) return false
    return h == l || h.endsWith(".$l") ||
        h == "challenges.cloudflare.com" || h.endsWith(".cloudflare.com")
}

/** Cookies and WebStorage are process-wide; hoster cookies must not linger there. */
private fun clearWebSession() {
    runCatching { CookieManager.getInstance().removeAllCookies(null) }
    runCatching { WebStorage.getInstance().deleteAllData() }
}

/**
 * Login in an embedded browser for hosters whose login form requires a
 * captcha (ddownload uses Cloudflare Turnstile). The WebView only loads the
 * login domain and the Cloudflare challenge; browser cookies and WebStorage
 * are cleared after accepting as well as after cancelling.
 *
 * A full screen rather than a dialog, because only then do the system insets
 * apply reliably; the buttons live in the top bar and cannot be pushed off
 * screen. [status] and [detectedCookies] belong to the ViewModel because the
 * WebView does not survive rotation.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebLoginScreen(
    loginUrl: String,
    status: String,
    detectedCookies: String?,
    onStatusChange: (String) -> Unit,
    onCookiesDetected: (String) -> Unit,
    onCancel: () -> Unit,
    onAccept: (cookies: String) -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    fun cancel() {
        clearWebSession()
        onCancel()
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else cancel()
    }

    val loginHost = remember(loginUrl) { loginUrl.toUri().host.orEmpty() }

    fun cookiesFor(url: String): String? =
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }

    /** XFileSharing sets xfss/xfsts after a successful login. */
    fun looksLoggedIn(cookies: String?): Boolean =
        cookies != null && Regex("""\bxfs(s|ts)=""").containsMatchIn(cookies)

    fun allowed(host: String?): Boolean = isWebLoginHostAllowed(host, loginHost)

    fun accept(cookies: String) {
        onAccept(cookies)
        clearWebSession()
    }

    Scaffold(
        // Include the IME so the form stays above the keyboard.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linkgrabber_login_title)) },
                navigationIcon = {
                    IconButton(onClick = { cancel() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
                actions = {
                    val noSession = stringResource(R.string.linkgrabber_login_no_session)
                    TextButton(onClick = {
                        val c = detectedCookies ?: cookiesFor(loginUrl)
                        if (c.isNullOrBlank()) {
                            onStatusChange(noSession)
                        } else {
                            accept(c)
                        }
                    }) { Text(stringResource(R.string.linkgrabber_login_accept)) }
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
                        // Required by the Cloudflare challenge.
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val host = request?.url?.host
                                if (allowed(host)) return false
                                onStatusChange(
                                    context.getString(
                                        R.string.linkgrabber_login_blocked,
                                        host ?: context.getString(R.string.linkgrabber_unknown_address),
                                        loginHost
                                    )
                                )
                                return true
                            }

                            // Sub-resources bypass shouldOverrideUrlLoading; runs
                            // on a background thread, hence no status update.
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (allowed(request?.url?.host)) return null
                                return WebResourceResponse(
                                    "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                val c = cookiesFor(url ?: loginUrl)
                                if (looksLoggedIn(c)) {
                                    onCookiesDetected(c!!)
                                    onStatusChange(context.getString(R.string.linkgrabber_login_detected))
                                }
                            }
                        }
                        loadUrl(loginUrl)
                        webView = this
                    }
                },
                // Otherwise the WebView and its renderer keep running.
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.stopLoading()
                    view.destroy()
                }
            )
        }
    }
}
