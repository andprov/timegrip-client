package ru.timegrip.app.ui.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.timegrip.app.R
import ru.timegrip.app.data.prefs.SessionStore
import ru.timegrip.app.data.repository.AccountRepository
import ru.timegrip.app.data.repository.AuthRepository
import ru.timegrip.app.domain.formatCountdown
import ru.timegrip.app.ui.common.AppLogo
import ru.timegrip.app.ui.common.Banner
import ru.timegrip.app.ui.common.BannerKind
import ru.timegrip.app.ui.common.ErrorBanner
import ru.timegrip.app.ui.common.UiMessage
import ru.timegrip.app.ui.common.appViewModel

class ActivationViewModel(
    private val account: AccountRepository,
    private val auth: AuthRepository,
    val sessionStore: SessionStore,
) : ViewModel() {
    var code by mutableStateOf("")
    var activating by mutableStateOf(false)
        private set
    var activateError by mutableStateOf<UiMessage?>(null)
        private set
    var resending by mutableStateOf(false)
        private set
    var resendError by mutableStateOf<UiMessage?>(null)
        private set
    var codeResent by mutableStateOf(false)
        private set

    /** The server knows when the last code went out, so the countdown survives restarts. */
    var cooldownLoaded by mutableStateOf(false)
        private set
    var cooldownSeconds by mutableIntStateOf(0)
        private set
    var cooldownSetAt by mutableLongStateOf(0L)
        private set

    init {
        loadCooldown()
    }

    private fun loadCooldown() {
        viewModelScope.launch {
            runCatching { account.resendCooldownSeconds() }.onSuccess {
                cooldownSeconds = it
                cooldownSetAt = System.currentTimeMillis()
            }
            cooldownLoaded = true
        }
    }

    fun activate() {
        if (code.isBlank()) {
            activateError = UiMessage.Res(R.string.error_required)
            return
        }
        viewModelScope.launch {
            activating = true
            activateError = null
            try {
                account.activate(code)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                activateError = UiMessage.of(failure)
            } finally {
                activating = false
            }
        }
    }

    fun resend() {
        viewModelScope.launch {
            resending = true
            resendError = null
            codeResent = false
            try {
                account.resendActivationCode()
                codeResent = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                resendError = UiMessage.of(failure)
            } finally {
                // Whether sent or rejected, the fresh cooldown takes over the button.
                runCatching { account.resendCooldownSeconds() }.onSuccess {
                    cooldownSeconds = it
                    cooldownSetAt = System.currentTimeMillis()
                }
                resending = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}

/** Web: components/ActivationGate.tsx. */
@Composable
fun ActivationScreen() {
    val vm = appViewModel { ActivationViewModel(it.accountRepository, it.authRepository, it.sessionStore) }
    val session by vm.sessionStore.state.collectAsState()
    var secondsLeft by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.cooldownSeconds, vm.cooldownSetAt) {
        while (true) {
            val elapsed = ((System.currentTimeMillis() - vm.cooldownSetAt) / 1000).toInt()
            secondsLeft = (vm.cooldownSeconds - elapsed).coerceAtLeast(0)
            if (secondsLeft == 0) break
            delay(1000)
        }
    }
    // A rejection while the countdown runs is already explained by it.
    val resendError = if (secondsLeft > 0) null else vm.resendError

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppLogo(size = 40.dp)
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.activation_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.activation_description, session.user?.email.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = vm.code,
                    onValueChange = { vm.code = it },
                    label = { Text(stringResource(R.string.activation_code_label)) },
                    placeholder = { Text("123456") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorBanner(vm.activateError)
                Button(onClick = vm::activate, enabled = !vm.activating, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.activate))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (vm.codeResent) Banner(stringResource(R.string.code_resent), kind = BannerKind.SUCCESS)
                ErrorBanner(resendError)
                OutlinedButton(
                    onClick = vm::resend,
                    enabled = vm.cooldownLoaded && !vm.resending && secondsLeft == 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (secondsLeft > 0) {
                            stringResource(R.string.resend_code_in, formatCountdown(secondsLeft))
                        } else {
                            stringResource(R.string.resend_code)
                        },
                    )
                }
                TextButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sign_out))
                }
            }
        }
    }
}
