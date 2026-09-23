package ru.timegrip.app.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.timegrip.app.R
import ru.timegrip.app.data.prefs.SettingsStore
import ru.timegrip.app.data.repository.AuthRepository
import ru.timegrip.app.data.repository.Locales
import ru.timegrip.app.ui.common.UiMessage

/** Shared shape of the auth forms: one request at a time, one error line. */
abstract class FormViewModel : ViewModel() {
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiMessage?>(null)
        protected set

    protected fun submit(block: suspend () -> Unit) {
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                error = UiMessage.of(failure)
            } finally {
                loading = false
            }
        }
    }

    protected fun requireFilled(vararg values: String): Boolean {
        if (values.any { it.isBlank() }) {
            error = UiMessage.Res(R.string.error_required)
            return false
        }
        return true
    }
}

class SignInViewModel(private val auth: AuthRepository, val settings: SettingsStore) : FormViewModel() {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var unsyncedChanges by mutableIntStateOf(0)
        private set

    init {
        viewModelScope.launch { unsyncedChanges = auth.unsyncedChangesCount() }
    }

    fun signIn() {
        if (!requireFilled(email, password)) return
        submit { auth.signIn(email, password) }
    }
}

class SignUpViewModel(private val auth: AuthRepository) : FormViewModel() {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var confirmPassword by mutableStateOf("")

    fun signUp() {
        if (!requireFilled(email, password, confirmPassword)) return
        if (password != confirmPassword) {
            error = UiMessage.Res(R.string.passwords_do_not_match)
            return
        }
        // The account starts in the language the app is shown in (web: SignUpPage).
        submit { auth.signUp(email, password, Locales.current()) }
    }
}

class ForgotPasswordViewModel(private val auth: AuthRepository) : FormViewModel() {
    var email by mutableStateOf("")

    fun send(onSent: (String) -> Unit) {
        if (!requireFilled(email)) return
        submit {
            auth.forgotPassword(email)
            onSent(email.trim())
        }
    }
}

class ResetPasswordViewModel(private val auth: AuthRepository, initialEmail: String) : FormViewModel() {
    var email by mutableStateOf(initialEmail)
    var code by mutableStateOf("")
    var password by mutableStateOf("")
    var confirmPassword by mutableStateOf("")

    fun reset(onDone: () -> Unit) {
        if (!requireFilled(email, code, password, confirmPassword)) return
        if (password != confirmPassword) {
            error = UiMessage.Res(R.string.passwords_do_not_match)
            return
        }
        submit {
            auth.resetPassword(email, code, password)
            onDone()
        }
    }
}
