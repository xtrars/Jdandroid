package com.jdandroid.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Anmeldung im eingebetteten Browser. Notwendig bei Hostern, deren
 * Login-Formular ein CAPTCHA verlangt (ddownload nutzt Cloudflare Turnstile):
 * headless ist das nicht loesbar. Nach erfolgreicher Anmeldung werden die
 * Session-Cookies uebernommen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginDialog(
    loginUrl: String,
    onDismiss: () -> Unit,
    onLoggedIn: (cookies: String) -> Unit
) {
    var status by remember { mutableStateOf("Bitte anmelden (inkl. \"Ich bin kein Roboter\") …") }
    var detectedCookies by remember { mutableStateOf<String?>(null) }

    fun cookiesFor(url: String): String? =
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }

    /** Session erkannt? XFileSharing setzt xfss/xfsts nach erfolgreichem Login. */
    fun looksLoggedIn(cookies: String?): Boolean =
        cookies != null && Regex("""\bxfs(s|ts)=""").containsMatchIn(cookies)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    status,
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val c = cookiesFor(url ?: loginUrl)
                                    if (looksLoggedIn(c)) {
                                        detectedCookies = c
                                        status = "Anmeldung erkannt – auf \"Übernehmen\" tippen."
                                    }
                                }
                            }
                            loadUrl(loginUrl)
                        }
                    }
                )
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Abbrechen")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val c = detectedCookies ?: cookiesFor(loginUrl)
                            if (c.isNullOrBlank()) {
                                status = "Noch keine Session gefunden – bitte zuerst anmelden."
                            } else {
                                onLoggedIn(c)
                            }
                        }
                    ) { Text("Übernehmen") }
                }
            }
        }
    }
}
