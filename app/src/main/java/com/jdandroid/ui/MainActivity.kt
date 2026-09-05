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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
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
import com.jdandroid.container.ContainerFiles
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
    private val dlcContent = mutableStateOf<String?>(null)

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
                        onSharedTextConsumed = { sharedText.value = null },
                        dlcContent = dlcContent.value,
                        onDlcConsumed = { dlcContent.value = null }
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
            DownloadService.send(this, DownloadService.ACTION_RESUME_ALL)
        }
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
        if (uri != null) {
            // Off the main thread: the filter accepts any octet-stream file,
            // and cloud providers download it on open.
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching { ContainerFiles.readText(contentResolver, uri) }
                val content = result.getOrNull()
                if (content != null && ContainerFiles.looksLikeDlc(content)) {
                    withContext(Dispatchers.Main) { dlcContent.value = content }
                } else {
                    AppMessages.error(
                        result.exceptionOrNull()?.message
                            ?: getString(R.string.accounts_not_dlc_opened)
                    )
                }
            }
        }
    }
}

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
    onSharedTextConsumed: () -> Unit,
    dlcContent: String?,
    onDlcConsumed: () -> Unit
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
    LaunchedEffect(dlcContent) {
        if (dlcContent != null) {
            downloadVm.importDlc(dlcContent)
            onDlcConsumed()
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

    Scaffold(
        // Inner screens (own TopAppBar) and the NavigationBar handle the insets;
        // otherwise the padding would be applied twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { JdSnackbarHost(messageHost) },
        // FAB in the outer Scaffold so the snackbar pushes it up instead of covering it.
        floatingActionButton = {
            when (tab) {
                Tab.Collector -> FloatingActionButton(
                    onClick = { addLinksPrefill = ""; showAddLinks = true }
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.accounts_add_links)) }
                Tab.Accounts -> FloatingActionButton(
                    onClick = { showAddAccount = true }
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
        val modifier = Modifier.padding(padding)
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
