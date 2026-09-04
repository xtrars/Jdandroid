package com.jdandroid.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Anmeldung im eingebetteten Browser. Notwendig bei Hostern, deren
 * Login-Formular ein CAPTCHA verlangt (ddownload nutzt Cloudflare Turnstile):
 * headless ist das nicht loesbar. Nach erfolgreicher Anmeldung werden die
 * Session-Cookies uebernommen.
 *
 * Sicherheit: Die WebView laedt nur Seiten der Login-Domain und der
 * Cloudflare-Challenge; Drittanbieter-Cookies sind aus, und nach der
 * Uebernahme werden die Browser-Cookies wieder geloescht (die Session liegt
 * dann verschluesselt in der Datenbank).
 *
 * Bewusst ein vollwertiger Bildschirm statt eines Vollbild-Dialogs: nur so
 * greifen die System-Insets zuverlaessig. Die Schaltflaechen sitzen in der
 * Titelleiste und koennen daher nie aus dem sichtbaren Bereich rutschen.
 *
 * Statuszeile und erkannte Session kommen aus dem ViewModel ([status],
 * [detectedCookies]): die WebView selbst ueberlebt kein Drehen, die
 * Erkennung der Anmeldung soll aber nicht verloren gehen.
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
    // Referenz auf die aktuelle WebView fuer die Zurueck-Taste (zuerst in der
    // Seitenhistorie zurueck, erst dann den Login abbrechen).
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onCancel()
    }

    val loginHost = remember(loginUrl) { android.net.Uri.parse(loginUrl).host.orEmpty() }

    fun cookiesFor(url: String): String? =
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }

    /** XFileSharing setzt xfss/xfsts nach erfolgreichem Login. */
    fun looksLoggedIn(cookies: String?): Boolean =
        cookies != null && Regex("""\bxfs(s|ts)=""").containsMatchIn(cookies)

    /** Nur die Login-Domain (inkl. Subdomains) und die Cloudflare-Challenge. */
    fun allowed(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == loginHost || h.endsWith(".$loginHost") ||
            h == "challenges.cloudflare.com" || h.endsWith(".cloudflare.com")
    }

    fun accept(cookies: String) {
        onAccept(cookies)
        // Browser-Cookies nach der Uebernahme entfernen: die Session bleibt
        // nur noch verschluesselt in der App gespeichert.
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }

    Scaffold(
        // Tastatur-Insets mitnehmen, damit das Login-Formular ueber der
        // Bildschirmtastatur bleibt statt dahinter zu verschwinden.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = { Text("Im Browser anmelden") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Abbrechen")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val c = detectedCookies ?: cookiesFor(loginUrl)
                        if (c.isNullOrBlank()) {
                            onStatusChange("Noch keine Session gefunden – bitte zuerst anmelden.")
                        } else {
                            accept(c)
                        }
                    }) { Text("Übernehmen") }
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
                        // JavaScript ist fuer die Cloudflare-Challenge zwingend
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
                                    "Blockiert: ${host ?: "unbekannte Adresse"} " +
                                        "(nur $loginHost ist erlaubt)."
                                )
                                return true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                val c = cookiesFor(url ?: loginUrl)
                                if (looksLoggedIn(c)) {
                                    onCookiesDetected(c!!)
                                    onStatusChange("Anmeldung erkannt – oben auf \"Übernehmen\" tippen.")
                                }
                            }
                        }
                        loadUrl(loginUrl)
                        webView = this
                    }
                },
                // Beim Verlassen (Abbrechen, Uebernehmen, Drehen) die WebView
                // sauber beenden, sonst laeuft sie samt Renderer weiter.
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.stopLoading()
                    view.destroy()
                }
            )
        }
    }
}
