package ru.timegrip.app.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.timegrip.app.BuildConfig
import ru.timegrip.app.R
import ru.timegrip.app.data.prefs.SessionState
import ru.timegrip.app.data.prefs.SettingsStore
import ru.timegrip.app.data.repository.AccountRepository
import ru.timegrip.app.data.repository.AuthRepository
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.domain.AppLocale
import ru.timegrip.app.domain.Session
import ru.timegrip.app.domain.ThemePreference
import ru.timegrip.app.domain.TimeFormat
import ru.timegrip.app.domain.formatDateTime
import ru.timegrip.app.ui.common.Banner
import ru.timegrip.app.ui.common.ErrorBanner
import ru.timegrip.app.ui.common.PanelCard
import ru.timegrip.app.ui.common.PasswordField
import ru.timegrip.app.ui.common.UiMessage
import ru.timegrip.app.ui.common.appViewModel
import ru.timegrip.app.ui.common.text

class AccountViewModel(
    private val account: AccountRepository,
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    syncManager: SyncManager,
    val session: StateFlow<SessionState>,
) : ViewModel() {
    val theme = settings.theme
    val apiBaseUrl = settings.apiBaseUrl
    val isOnline = syncManager.isOnline

    var sessions by mutableStateOf<List<Session>?>(null)
        private set
    var sessionsError by mutableStateOf<UiMessage?>(null)
        private set
    var actionError by mutableStateOf<UiMessage?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    init {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            sessionsError = null
            try {
                sessions = account.sessions()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                sessionsError = UiMessage.of(failure)
            }
        }
    }

    fun setTheme(value: ThemePreference) = settings.setTheme(value)

    fun setTimeFormat(value: TimeFormat) {
        viewModelScope.launch { account.setTimeFormat(value) }
    }

    fun setLocale(value: AppLocale) {
        viewModelScope.launch { account.setLocale(value) }
    }

    fun revoke(id: Long) = act {
        account.revokeSession(id)
        sessions = account.sessions()
    }

    fun revokeAll() = act { account.revokeAllSessions() }

    fun deleteAccount() = act { account.deleteAccount() }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    suspend fun unsyncedChanges(): Int = auth.unsyncedChangesCount()

    /** Runs [block] for a dialog; [onSuccess] closes it. Errors land in [dialogError]. */
    var dialogError by mutableStateOf<UiMessage?>(null)
        private set

    fun changeEmail(password: String, email: String, onSuccess: () -> Unit) = dialogAct(onSuccess) {
        account.changeEmail(password, email)
    }

    fun changePassword(current: String, new: String, onSuccess: () -> Unit) = dialogAct(onSuccess) {
        account.changePassword(current, new)
        sessions = runCatching { account.sessions() }.getOrDefault(sessions)
    }

    fun clearDialogError() {
        dialogError = null
    }

    private fun dialogAct(onSuccess: () -> Unit, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            dialogError = null
            try {
                block()
                onSuccess()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                dialogError = UiMessage.of(failure)
            } finally {
                busy = false
            }
        }
    }

    private fun act(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            actionError = null
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                actionError = UiMessage.of(failure)
            } finally {
                busy = false
            }
        }
    }
}

private enum class AccountDialog { EMAIL, PASSWORD, THEME, TIME_FORMAT, LANGUAGE, LOGOUT_EVERYWHERE, DELETE, SIGN_OUT }

/** Web: pages/AccountPage.tsx as a Material settings list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen() {
    val vm = appViewModel {
        AccountViewModel(it.accountRepository, it.authRepository, it.settingsStore, it.syncManager, it.sessionStore.state)
    }
    val session by vm.session.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val apiUrl by vm.apiBaseUrl.collectAsStateWithLifecycle()
    val online by vm.isOnline.collectAsStateWithLifecycle()
    val user = session.user ?: return
    var dialog by rememberSaveable { mutableStateOf<AccountDialog?>(null) }
    var sessionsExpanded by rememberSaveable { mutableStateOf(false) }
    var unsynced by remember { mutableIntStateOf(0) }

    LaunchedEffect(online) { if (online && vm.sessions == null) vm.loadSessions() }

    val needsNetwork = stringResource(R.string.requires_network)
    val themeLabels = mapOf(
        ThemePreference.LIGHT to stringResource(R.string.theme_light),
        ThemePreference.DARK to stringResource(R.string.theme_dark),
        ThemePreference.SYSTEM to stringResource(R.string.theme_system),
    )
    val timeFormatLabels = mapOf(
        TimeFormat.H12 to stringResource(R.string.time_format_12h),
        TimeFormat.H24 to stringResource(R.string.time_format_24h),
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ErrorBanner(vm.actionError) }
            item {
                PanelCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    Column {
                        SettingRow(
                            icon = Icons.Outlined.AccountCircle,
                            title = stringResource(R.string.signed_in_as),
                            value = user.email,
                        )
                        SettingRow(
                            icon = Icons.Outlined.Email,
                            title = stringResource(R.string.change_email),
                            value = if (online) null else needsNetwork,
                            enabled = online,
                        ) { dialog = AccountDialog.EMAIL }
                        SettingRow(
                            icon = Icons.Outlined.Lock,
                            title = stringResource(R.string.change_password),
                            value = if (online) null else needsNetwork,
                            enabled = online,
                        ) { dialog = AccountDialog.PASSWORD }
                    }
                }
            }
            item {
                PanelCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    Column {
                        SettingRow(Icons.Outlined.DarkMode, stringResource(R.string.theme), themeLabels.getValue(theme)) {
                            dialog = AccountDialog.THEME
                        }
                        SettingRow(Icons.Outlined.Schedule, stringResource(R.string.time_format), timeFormatLabels.getValue(user.timeFormat)) {
                            dialog = AccountDialog.TIME_FORMAT
                        }
                        SettingRow(Icons.Outlined.Language, stringResource(R.string.language), user.locale.displayName) {
                            dialog = AccountDialog.LANGUAGE
                        }
                    }
                }
            }
            item {
                PanelCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    Column {
                        val sessions = vm.sessions
                        SettingRow(
                            icon = Icons.Outlined.Devices,
                            title = stringResource(R.string.active_sessions),
                            value = when {
                                !online && sessions == null -> needsNetwork
                                sessions != null -> pluralStringResource(R.plurals.sessions_count, sessions.size, sessions.size)
                                else -> stringResource(R.string.active_sessions_desc)
                            },
                            trailing = if (sessions.isNullOrEmpty()) {
                                null
                            } else {
                                {
                                    Icon(
                                        if (sessionsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                    )
                                }
                            },
                            enabled = !sessions.isNullOrEmpty(),
                        ) { sessionsExpanded = !sessionsExpanded }
                        vm.sessionsError?.let { if (online) Banner(it.text(), Modifier.padding(horizontal = 16.dp)) }
                        if (sessionsExpanded && sessions != null) {
                            sessions.forEach { item ->
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                SessionRow(item, user.timeFormat, enabled = online && !vm.busy) { vm.revoke(item.id) }
                            }
                        }
                        OutlinedButton(
                            onClick = { dialog = AccountDialog.LOGOUT_EVERYWHERE },
                            enabled = online && !vm.busy && !sessions.isNullOrEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(stringResource(R.string.log_out_everywhere)) }
                    }
                }
            }
            item {
                PanelCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    Column {
                        SettingRow(Icons.Outlined.Dns, stringResource(R.string.server_row), apiUrl)
                        SettingRow(
                            icon = Icons.AutoMirrored.Outlined.Logout,
                            title = stringResource(R.string.sign_out),
                        ) { dialog = AccountDialog.SIGN_OUT }
                    }
                }
            }
            item {
                PanelCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.delete_account_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Text(
                            stringResource(R.string.delete_account_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { dialog = AccountDialog.DELETE },
                            enabled = online && !vm.busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.delete_account_title)) }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    val close = { dialog = null; vm.clearDialogError() }
    when (dialog) {
        AccountDialog.THEME -> ChoiceDialog(stringResource(R.string.theme), themeLabels, theme, onDismiss = close) {
            vm.setTheme(it)
            close()
        }
        AccountDialog.TIME_FORMAT -> ChoiceDialog(stringResource(R.string.time_format), timeFormatLabels, user.timeFormat, onDismiss = close) {
            vm.setTimeFormat(it)
            close()
        }
        AccountDialog.LANGUAGE -> ChoiceDialog(
            stringResource(R.string.language),
            AppLocale.entries.associateWith { it.displayName },
            user.locale,
            onDismiss = close,
        ) {
            vm.setLocale(it)
            close()
        }
        AccountDialog.EMAIL -> ChangeEmailDialog(vm, onDismiss = close)
        AccountDialog.PASSWORD -> ChangePasswordDialog(vm, onDismiss = close)
        AccountDialog.LOGOUT_EVERYWHERE -> ConfirmActionDialog(
            title = stringResource(R.string.log_out_everywhere),
            message = stringResource(R.string.logout_everywhere_confirm),
            confirmLabel = stringResource(R.string.log_out_everywhere),
            onDismiss = close,
        ) {
            close()
            vm.revokeAll()
        }
        AccountDialog.DELETE -> ConfirmActionDialog(
            title = stringResource(R.string.delete_account_title),
            message = stringResource(R.string.delete_account_confirm),
            confirmLabel = stringResource(R.string.delete_account_title),
            onDismiss = close,
        ) {
            close()
            vm.deleteAccount()
        }
        AccountDialog.SIGN_OUT -> {
            LaunchedEffect(Unit) { unsynced = vm.unsyncedChanges() }
            ConfirmActionDialog(
                title = stringResource(R.string.sign_out_confirm_title),
                message = if (unsynced > 0) pluralStringResource(R.plurals.sign_out_pending_message, unsynced, unsynced) else "",
                confirmLabel = stringResource(R.string.sign_out),
                destructive = unsynced > 0,
                onDismiss = close,
            ) {
                close()
                vm.signOut()
            }
        }
        null -> Unit
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    value: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it) } },
        leadingContent = {
            if (icon != null) Icon(icon, contentDescription = null) else Spacer(Modifier.width(24.dp))
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (enabled || onClick == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
    )
}

@Composable
private fun SessionRow(session: Session, timeFormat: TimeFormat, enabled: Boolean, onRevoke: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.userAgent ?: stringResource(R.string.unknown_device),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
            Text(
                "${session.ipAddress ?: stringResource(R.string.unknown_ip)} · ${formatDateTime(session.createdAt, timeFormat)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRevoke, enabled = enabled) { Text(stringResource(R.string.revoke)) }
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: Map<T, String>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        text = if (message.isNotEmpty()) {
            { Text(message) }
        } else {
            null
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Web: components/ChangeEmailModal.tsx. */
@Composable
private fun ChangeEmailDialog(vm: AccountViewModel, onDismiss: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_email)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.new_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(password, { password = it }, stringResource(R.string.current_password), imeAction = ImeAction.Done)
                ErrorBanner(vm.dialogError)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.changeEmail(password, email, onDismiss) },
                enabled = !vm.busy && email.isNotBlank() && password.isNotEmpty(),
            ) { Text(stringResource(R.string.update_email)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Web: components/ChangePasswordModal.tsx. */
@Composable
private fun ChangePasswordDialog(vm: AccountViewModel, onDismiss: () -> Unit) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasswordField(current, { current = it }, stringResource(R.string.current_password))
                PasswordField(
                    new,
                    { new = it },
                    stringResource(R.string.new_password),
                    imeAction = ImeAction.Done,
                    supportingText = stringResource(R.string.password_hint),
                )
                ErrorBanner(vm.dialogError)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.changePassword(current, new, onDismiss) },
                enabled = !vm.busy && current.isNotEmpty() && new.isNotEmpty(),
            ) { Text(stringResource(R.string.update_password)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
