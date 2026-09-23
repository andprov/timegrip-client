package ru.timegrip.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.timegrip.app.R
import ru.timegrip.app.data.prefs.SettingsStore
import ru.timegrip.app.ui.common.AppLogo
import ru.timegrip.app.ui.common.Banner
import ru.timegrip.app.ui.common.BannerKind
import ru.timegrip.app.ui.common.ErrorBanner
import ru.timegrip.app.ui.common.PasswordField
import ru.timegrip.app.ui.common.appViewModel

@Serializable
private data class SignInRoute(val notice: Int = 0)

@Serializable
private data object SignUpRoute

@Serializable
private data object ForgotPasswordRoute

@Serializable
private data class ResetPasswordRoute(val email: String = "", val codeSent: Boolean = false)

/** Sign-in, sign-up and password recovery. [sessionExpired]: signed out by the server. */
@Composable
fun AuthFlow(sessionExpired: Boolean) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = SignInRoute()) {
        composable<SignInRoute> { entry ->
            val route = entry.toRoute<SignInRoute>()
            SignInScreen(
                sessionExpired = sessionExpired,
                passwordReset = route.notice == NOTICE_PASSWORD_RESET,
                onSignUp = { navController.navigate(SignUpRoute) },
                onForgotPassword = { navController.navigate(ForgotPasswordRoute) },
            )
        }
        composable<SignUpRoute> {
            SignUpScreen(onSignIn = { navController.popBackStack() })
        }
        composable<ForgotPasswordRoute> {
            ForgotPasswordScreen(
                onBack = { navController.popBackStack() },
                onCodeSent = { email -> navController.navigate(ResetPasswordRoute(email, codeSent = true)) },
                onHaveCode = { email -> navController.navigate(ResetPasswordRoute(email)) },
            )
        }
        composable<ResetPasswordRoute> { entry ->
            val route = entry.toRoute<ResetPasswordRoute>()
            ResetPasswordScreen(
                email = route.email,
                codeSent = route.codeSent,
                onBack = { navController.popBackStack() },
                onDone = {
                    navController.navigate(SignInRoute(NOTICE_PASSWORD_RESET)) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}

private const val NOTICE_PASSWORD_RESET = 1

/** Web: components/layout/AuthLayout.tsx. */
@Composable
private fun AuthLayout(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(32.dp))
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
            }
        }
    }
}

@Composable
private fun SubmitButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (loading) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(20.dp).height(20.dp))
        } else {
            Text(text)
        }
    }
}

@Composable
private fun EmailField(value: String, onValueChange: (String) -> Unit, label: String = stringResource(R.string.email)) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SignInScreen(
    sessionExpired: Boolean,
    passwordReset: Boolean,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    val vm = appViewModel { SignInViewModel(it.authRepository, it.settingsStore) }
    val focus = LocalFocusManager.current
    var serverDialog by rememberSaveable { mutableStateOf(false) }
    val apiUrl by vm.settings.apiBaseUrl.collectAsState()

    AuthLayout(stringResource(R.string.sign_in)) {
        if (sessionExpired) {
            val text = buildString {
                append(stringResource(R.string.session_expired_text))
                if (vm.unsyncedChanges > 0) {
                    append(' ')
                    append(pluralStringResource(R.plurals.session_expired_pending, vm.unsyncedChanges, vm.unsyncedChanges))
                }
            }
            Banner(text, kind = BannerKind.INFO)
        }
        if (passwordReset) Banner(stringResource(R.string.password_reset_done), kind = BannerKind.SUCCESS)
        EmailField(vm.email, { vm.email = it })
        PasswordField(
            vm.password,
            { vm.password = it },
            stringResource(R.string.password),
            imeAction = ImeAction.Done,
        )
        ErrorBanner(vm.error)
        SubmitButton(stringResource(R.string.sign_in), vm.loading) {
            focus.clearFocus()
            vm.signIn()
        }
        TextButton(onClick = onForgotPassword, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.forgot_password_question))
        }
        TextButton(onClick = onSignUp, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.no_account_yet))
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { serverDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.server_label, serverLabel(apiUrl)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (serverDialog) ServerDialog(vm.settings, onDismiss = { serverDialog = false })
}

private fun serverLabel(apiUrl: String): String {
    val url = apiUrl.toHttpUrlOrNull() ?: return apiUrl
    val defaultPort = if (url.isHttps) 443 else 80
    return if (url.port == defaultPort) url.host else "${url.host}:${url.port}"
}

/** Which TimeGrip instance to talk to (self-hosted deployments, the local dev stack). */
@Composable
private fun ServerDialog(settings: SettingsStore, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(settings.apiBaseUrl.value) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    invalid = false
                },
                singleLine = true,
                isError = invalid,
                supportingText = {
                    Text(stringResource(if (invalid) R.string.server_invalid else R.string.server_hint))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (settings.setApiBaseUrl(url)) onDismiss() else invalid = true
                }),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (settings.setApiBaseUrl(url)) onDismiss() else invalid = true }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    settings.resetApiBaseUrl()
                    onDismiss()
                }) { Text(stringResource(R.string.server_default)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun SignUpScreen(onSignIn: () -> Unit) {
    val vm = appViewModel { SignUpViewModel(it.authRepository) }
    val focus = LocalFocusManager.current
    AuthLayout(stringResource(R.string.create_account)) {
        EmailField(vm.email, { vm.email = it })
        PasswordField(
            vm.password,
            { vm.password = it },
            stringResource(R.string.password),
            supportingText = stringResource(R.string.password_hint),
        )
        PasswordField(
            vm.confirmPassword,
            { vm.confirmPassword = it },
            stringResource(R.string.confirm_password),
            imeAction = ImeAction.Done,
            isError = vm.confirmPassword.isNotEmpty() && vm.confirmPassword != vm.password,
        )
        ErrorBanner(vm.error)
        SubmitButton(stringResource(R.string.sign_up), vm.loading) {
            focus.clearFocus()
            vm.signUp()
        }
        TextButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.already_have_account))
        }
    }
}

@Composable
private fun ForgotPasswordScreen(onBack: () -> Unit, onCodeSent: (String) -> Unit, onHaveCode: (String) -> Unit) {
    val vm = appViewModel { ForgotPasswordViewModel(it.authRepository) }
    AuthLayout(stringResource(R.string.forgot_password_title)) {
        EmailField(vm.email, { vm.email = it })
        ErrorBanner(vm.error)
        SubmitButton(stringResource(R.string.send_reset_code), vm.loading) { vm.send(onCodeSent) }
        TextButton(onClick = { onHaveCode(vm.email.trim()) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.have_a_code))
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back_to_sign_in))
        }
    }
}

@Composable
private fun ResetPasswordScreen(email: String, codeSent: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    val vm = appViewModel { ResetPasswordViewModel(it.authRepository, email) }
    AuthLayout(stringResource(R.string.reset_password)) {
        if (codeSent) Banner(stringResource(R.string.reset_code_sent_message, email), kind = BannerKind.INFO)
        EmailField(vm.email, { vm.email = it })
        OutlinedTextField(
            value = vm.code,
            onValueChange = { vm.code = it },
            label = { Text(stringResource(R.string.reset_code)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            vm.password,
            { vm.password = it },
            stringResource(R.string.new_password),
            supportingText = stringResource(R.string.password_hint),
        )
        PasswordField(
            vm.confirmPassword,
            { vm.confirmPassword = it },
            stringResource(R.string.confirm_password),
            imeAction = ImeAction.Done,
            isError = vm.confirmPassword.isNotEmpty() && vm.confirmPassword != vm.password,
        )
        ErrorBanner(vm.error)
        SubmitButton(stringResource(R.string.reset_password), vm.loading) { vm.reset(onDone) }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back_to_sign_in))
        }
    }
}
