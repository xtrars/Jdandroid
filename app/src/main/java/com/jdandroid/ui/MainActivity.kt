package com.jdandroid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jdandroid.CrashReporter
import com.jdandroid.JdApp
import com.jdandroid.R
import com.jdandroid.core.AppMessages
import com.jdandroid.engine.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val sharedText = mutableStateOf<String?>(null)
    // DLC files are read in the ViewModel so rotation cannot cancel the import.
    private val downloadVm: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 enforces edge-to-edge; without this the insets are not set up.
        enableEdgeToEdge()
        // Only on first creation, or the system dialog reappears on every rotation.
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // After recreation getIntent() still returns the old VIEW/SEND intent.
        if (savedInstanceState == null) handleIntent(intent)
        handleResumeRequest(intent)
        // Click'n'Load does not survive a process restart; restart the listener
        // and kick pending downloads (e.g. after a reboot).
        lifecycleScope.launch {
            val app = application as JdApp
            if (app.settings.currentClickNLoadEnabled()) {
                DownloadService.send(this@MainActivity, DownloadService.ACTION_START_CNL)
            }
            // openCount includes stuck RUNNING/EXTRACTING entries; the service resets them.
            if (withContext(Dispatchers.IO) { app.db.downloadDao().openCount() } > 0) {
                DownloadService.send(this@MainActivity, DownloadService.ACTION_PUMP)
            }
        }
        val settings = (application as JdApp).settings
        // Read synchronously, or the first frame flashes the system scheme.
        val initialMode = try {
            runBlocking { settings.themeMode.first() }
        } catch (e: Exception) {
            "system"
        }
        setContent {
            val modeKey by settings.themeMode.collectAsStateWithLifecycle(initialValue = initialMode)
            val mode = ThemeMode.fromKey(modeKey)
            val dark = isDarkFor(mode)
            // System bars follow the chosen mode, even when it differs from the system.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            JdTheme(mode = mode) {
                // Prevents a differently colored strip from flashing on rotation.
                val background = MaterialTheme.colorScheme.background
                LaunchedEffect(background) {
                    window.setBackgroundDrawable(background.toArgb().toDrawable())
                }
                Surface(color = background) {
                    MainScreen(
                        sharedText = sharedText.value,
                        onSharedTextConsumed = { sharedText.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        handleResumeRequest(intent)
    }

    /**
     * "Resume" from the time-limit notification: after the 6-hour limit,
     * Android 15 allows the service to restart only from the foreground,
     * hence via the Activity rather than a PendingIntent to the service.
     */
    private fun handleResumeRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(DownloadService.EXTRA_RESUME_ALL, false) == true) {
            intent.removeExtra(DownloadService.EXTRA_RESUME_ALL)
            // The activity is exported: only the app's own PendingIntents may control downloads.
            if (launchedFromOwnApp()) DownloadService.send(this, DownloadService.ACTION_RESUME_ALL)
        }
    }

    /**
     * Whether the current launch (or the intent in onNewIntent) came from this
     * app. For a PendingIntent the system reports its creator as referrer.
     */
    private fun launchedFromOwnApp(): Boolean {
        // getReferrer() prefers caller-supplied extras, which any app can forge.
        intent?.removeExtra(Intent.EXTRA_REFERRER)
        intent?.removeExtra(Intent.EXTRA_REFERRER_NAME)
        return isOwnAppReferrer(referrer?.toString(), packageName)
    }

    /** Routes incoming intents to shared text or DLC files. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) { sharedText.value = text; return }
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) downloadVm.openDlc(contentResolver, uri)
    }
}

/** True for the referrer the system sets when this app itself starts the activity. */
internal fun isOwnAppReferrer(referrer: String?, packageName: String): Boolean =
    referrer == "android-app://$packageName"

/** Tabs of the bottom navigation bar. */
private enum class Tab(val labelRes: Int) {
    Downloads(R.string.accounts_tab_downloads),
    Collector(R.string.accounts_tab_collector),
    Accounts(R.string.accounts_title),
    Settings(R.string.accounts_tab_settings)
}

@Composable
fun MainScreen(
    sharedText: String?,
    onSharedTextConsumed: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Downloads) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings = (context.applicationContext as JdApp).settings
    val downloadVm: DownloadViewModel = viewModel()
    val accountVm: AccountViewModel = viewModel()

    // Dialog state lives here so it survives rotation and tab switches.
    var showAddLinks by rememberSaveable { mutableStateOf(false) }
    var addLinksPrefill by rememberSaveable { mutableStateOf("") }
    var showAddAccount by rememberSaveable { mutableStateOf(false) }

    // Import regardless of the open tab, then switch to where the links land.
    val dlcContent by downloadVm.pendingDlc.collectAsStateWithLifecycle()
    LaunchedEffect(dlcContent) {
        dlcContent?.let { content ->
            downloadVm.importDlc(content)
            downloadVm.consumePendingDlc()
            tab = if (settings.currentAutoStartLinks()) Tab.Downloads else Tab.Collector
        }
    }
    // The dialog belongs to the collector tab; switch there so it is visible.
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            addLinksPrefill = sharedText
            showAddLinks = true
            onSharedTextConsumed()
            tab = Tab.Collector
        }
    }
    // Back goes to Downloads first, then leaves the app. Screens register their
    // own handlers later and thus take precedence.
    BackHandler(enabled = tab != Tab.Downloads) { tab = Tab.Downloads }

    // A progress message stays until the next message replaces it.
    val messageHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // Cancel the showing job rather than dismissing currentSnackbarData: a
        // result arriving right after a progress message would find it not yet
        // visible and leave it up forever.
        var showing: Job? = null
        AppMessages.events.collect { message ->
            AppMessages.markShown()
            showing?.cancel()
            showing = launch { messageHost.showSnackbar(JdMessage(message.text, message.kind)) }
        }
    }
    var crashReport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashReport = withContext(Dispatchers.IO) { CrashReporter.lastCrash(context) }
    }
    crashReport?.let { report ->
        CrashDialog(report = report, onDismiss = { crashReport = null })
    }

    // Browser login replaces the whole screen so the system insets apply.
    val webLoginHoster by accountVm.webLogin.collectAsStateWithLifecycle()
    val webLoginUrl = webLoginHoster?.webLoginUrl
    if (webLoginUrl != null) {
        val webLoginStatus by accountVm.webLoginStatus.collectAsStateWithLifecycle()
        val webLoginCookies by accountVm.webLoginCookies.collectAsStateWithLifecycle()
        WebLoginScreen(
            loginUrl = webLoginUrl,
            status = webLoginStatus,
            detectedCookies = webLoginCookies,
            onStatusChange = { accountVm.setWebLoginStatus(it) },
            onCookiesDetected = { accountVm.setWebLoginCookies(it) },
            onCancel = { accountVm.cancelWebLogin() },
            onAccept = { accountVm.completeWebLogin(it) }
        )
        return
    }

    val captcha by downloadVm.captcha.collectAsStateWithLifecycle()
    captcha?.let { request ->
        CaptchaScreen(
            pageUrl = request.page.url,
            cookieUrl = request.page.cookieUrl,
            cookies = request.page.cookies,
            hosterName = request.hoster.displayName,
            siteHosts = request.hoster.siteHosts,
            isDirectDownloadUrl = request.hoster::isDirectDownloadUrl,
            onCancel = { downloadVm.cancelCaptcha() },
            onDirectLink = { url, cookies -> downloadVm.completeCaptcha(url, cookies) }
        )
        return
    }

    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    Scaffold(
        // Inner screens (own TopAppBar) and the NavigationBar handle the insets;
        // otherwise the padding would be applied twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // Zero content insets leave FAB and snackbar under the side navigation
        // bar in landscape; they take the horizontal insets themselves.
        snackbarHost = { JdSnackbarHost(messageHost, Modifier.windowInsetsPadding(horizontalInsets)) },
        // FAB in the outer Scaffold so the snackbar pushes it up instead of covering it.
        floatingActionButton = {
            when (tab) {
                Tab.Collector -> FloatingActionButton(
                    onClick = { addLinksPrefill = ""; showAddLinks = true },
                    modifier = Modifier.windowInsetsPadding(horizontalInsets)
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.accounts_add_links)) }
                Tab.Accounts -> FloatingActionButton(
                    onClick = { showAddAccount = true },
                    modifier = Modifier.windowInsetsPadding(horizontalInsets)
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.accounts_add_title)) }
                else -> {}
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val label = stringResource(t.labelRes)
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Downloads -> JdIcons.Download
                                    Tab.Collector -> JdIcons.Link
                                    Tab.Accounts -> Icons.Default.Person
                                    Tab.Settings -> Icons.Default.Settings
                                },
                                contentDescription = label
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        // Consumed so imePadding in a tab pads only the part not already covered by the bottom bar.
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        // Keeps per-tab state (search, filters, collapsed packages, scroll position).
        val holder = rememberSaveableStateHolder()
        holder.SaveableStateProvider(tab.name) {
            when (tab) {
                Tab.Downloads -> DownloadsScreen(downloadVm, modifier)
                Tab.Collector -> LinkGrabberScreen(downloadVm, modifier)
                Tab.Accounts -> AccountsScreen(
                    accountVm,
                    showAdd = showAddAccount,
                    onShowAddChange = { showAddAccount = it },
                    modifier = modifier
                )
                Tab.Settings -> SettingsScreen(modifier)
            }
        }
    }

    if (showAddLinks) {
        // A new prefill (shared text) resets the dialog text.
        key(addLinksPrefill) {
            AddLinksDialog(
                initialText = addLinksPrefill,
                onDismiss = { showAddLinks = false },
                onAdd = { text, pkg ->
                    // The callback runs outside composition; no stringResource here.
                    downloadVm.addLinks(text, pkg) { added, toCollector ->
                        if (added > 0) {
                            AppMessages.success(
                                resources.getQuantityString(
                                    if (toCollector) R.plurals.accounts_links_collected
                                    else R.plurals.accounts_links_started,
                                    added, added
                                )
                            )
                            if (!toCollector) tab = Tab.Downloads
                        } else {
                            AppMessages.info(resources.getString(R.string.accounts_no_new_links))
                        }
                    }
                }
            )
        }
    }
}
