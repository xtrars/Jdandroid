package com.jdandroid.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jdandroid.R
import com.jdandroid.core.AppMessages
import com.jdandroid.core.formatBytes
import com.jdandroid.data.Account
import com.jdandroid.data.hasPremium
import com.jdandroid.hoster.AccountType
import com.jdandroid.hoster.Hoster
import com.jdandroid.hoster.HosterRegistry
import java.text.DateFormat
import java.util.Date

/** Accent for avatars and selection: the Material You primary, not brand colors. */
@Composable
private fun hosterColor(@Suppress("UNUSED_PARAMETER") id: String): Color =
    MaterialTheme.colorScheme.primary

@Composable
private fun HosterAvatar(hoster: Hoster, size: Int = 44) {
    // Rapidgator and ddownload icons fill their tile; the 1fichier cubes need a surface.
    val icon = hosterIconRes(hoster.id)
    val background = when (hoster.id) {
        "rapidgator", "ddownload" -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (icon != null) background else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Image(
                painterResource(icon),
                contentDescription = hoster.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(if (hoster.id == "onefichier") (size * 0.8f).dp else size.dp)
            )
        } else {
            Text(
                hoster.displayName.first().uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Account list. The add dialog is controlled via [showAdd] from the
 * MainActivity, whose outer Scaffold owns the FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    vm: AccountViewModel,
    showAdd: Boolean,
    onShowAddChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    // Refresh once on open, then every minute while the tab is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshAll()
                delay(60_000)
            }
        }
    }
    LaunchedEffect(message) {
        message?.let {
            AppMessages.error(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.accounts_title)) }, colors = jdTopBarColors()) }
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        if (accounts.isEmpty()) {
            Box(content.padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.accounts_empty_hint),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                content,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // Bottom padding keeps the FAB off the last card.
                contentPadding = PaddingValues(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 88.dp)
            ) {
                items(accounts, key = { it.id }) { account -> AccountRow(account, vm) }
            }
        }
    }

    if (showAdd) {
        AddAccountDialog(vm, onDismiss = { onShowAddChange(false) })
    }
}

@Composable
private fun AccountRow(account: Account, vm: AccountViewModel) {
    val hoster = HosterRegistry.byId(account.hosterId)
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val isPremium = account.hasPremium()

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            hoster?.let { HosterAvatar(it) }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hoster?.displayName ?: account.hosterId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    account.username ?: stringResource(
                        if (account.cookies != null) R.string.accounts_browser_login
                        else R.string.accounts_api_key_stored
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Scheme colors: tertiary = premium, secondary = valid without premium, error = invalid.
                    val (icon, tint) = when {
                        isPremium -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.tertiary
                        account.valid -> JdIcons.Error to MaterialTheme.colorScheme.secondary
                        account.lastChecked > 0 -> JdIcons.Error to MaterialTheme.colorScheme.error
                        else -> Icons.Default.Refresh to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    val status = account.statusText ?: stringResource(R.string.accounts_not_checked)
                    val details = if (isPremium && account.premiumUntil > 0) {
                        stringResource(
                            R.string.accounts_status_premium_until,
                            status,
                            dateFormat.format(Date(account.premiumUntil))
                        )
                    } else {
                        status
                    }
                    Text(details, style = MaterialTheme.typography.bodySmall, color = tint)
                }
                if (account.valid) {
                    Spacer(Modifier.height(6.dp))
                    TrafficLine(account)
                }
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.accounts_account_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.accounts_check)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = { menuOpen = false; vm.check(account.id) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; confirmDelete = true }
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.accounts_delete_title),
            text = stringResource(
                R.string.accounts_delete_text,
                hoster?.displayName ?: account.hosterId,
                account.username ?: stringResource(
                    if (account.cookies != null) R.string.accounts_browser_login
                    else R.string.accounts_api_key
                )
            ),
            onConfirm = { vm.delete(account) },
            onDismiss = { confirmDelete = false }
        )
    }
}

/** Remaining traffic of the account; "unlimited" for hosters without a limit. */
@Composable
private fun TrafficLine(account: Account) {
    val left = account.trafficLeft
    // Ticking clock: unlimited accounts are rechecked only every 15 min, but
    // "X min ago" must stay correct.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val minutesAgo = ((now - account.lastChecked) / 60_000L).coerceAtLeast(0)
    // Only a stale value gets the age suffix.
    val amount = when {
        account.trafficUnlimited -> stringResource(R.string.accounts_traffic_unlimited)
        left >= 0 -> formatBytes(left)
        else -> stringResource(R.string.accounts_traffic_unknown)
    }
    val text = when {
        account.lastChecked == 0L || minutesAgo < 2 -> amount
        minutesAgo < 60 -> stringResource(R.string.accounts_traffic_age_minutes, amount, minutesAgo)
        else -> stringResource(R.string.accounts_traffic_age_hours, amount, minutesAgo / 60)
    }
    val low = left in 0 until (1L shl 30) && !account.trafficUnlimited
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    )
    val total = account.trafficTotal
    if (!account.trafficUnlimited && left >= 0 && total > 0) {
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (left.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun AddAccountDialog(vm: AccountViewModel, onDismiss: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    val hoster = vm.hosters.firstOrNull { it.id == selectedId }
    val valid = hoster != null && if (hoster.accountType == AccountType.USERNAME_PASSWORD) {
        username.isNotBlank() && password.isNotBlank()
    } else {
        apiKey.isNotBlank()
    }

    // Bounded, scrolling content keeps title and buttons visible with the keyboard open.
    val maxContentHeight = windowHeightDp() * 0.45f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accounts_add_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.accounts_choose_hoster),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                vm.hosters.forEach { h ->
                    HosterSelectCard(
                        hoster = h,
                        selected = selectedId == h.id,
                        onClick = { selectedId = h.id }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                hoster?.let { h ->
                    Spacer(Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text(
                            h.accountHint,
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (h.accountType == AccountType.USERNAME_PASSWORD) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.accounts_username_label)) },
                            leadingIcon = { Icon(Icons.Default.Person, null) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                autoCorrectEnabled = false
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.accounts_password_label)) },
                            leadingIcon = { Icon(JdIcons.Key, null) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(stringResource(R.string.accounts_api_key)) },
                            leadingIcon = { Icon(JdIcons.Key, null) },
                            // Uri keyboard: no autocorrect, no space after a period.
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (h.webLoginUrl != null) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { vm.requestWebLogin(h); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Person, null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.accounts_web_login_button))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    hoster?.let { vm.addAccount(it, username, password, apiKey) }
                    onDismiss()
                }
            ) { Text(stringResource(R.string.accounts_save_and_check)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun HosterSelectCard(hoster: Hoster, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(
        if (selected) hosterColor(hoster.id) else MaterialTheme.colorScheme.outlineVariant,
        label = "border"
    )
    // selectable instead of onClick: screen readers announce the radio role and state.
    Card(
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            HosterAvatar(hoster)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hoster.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(
                        when (hoster.accountType) {
                            AccountType.USERNAME_PASSWORD -> R.string.accounts_login_user_password
                            AccountType.API_KEY -> R.string.accounts_login_api_key
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.accounts_selected),
                    tint = hosterColor(hoster.id)
                )
            }
        }
    }
}
