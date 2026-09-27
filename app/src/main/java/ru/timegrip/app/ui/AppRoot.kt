package ru.timegrip.app.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.timegrip.app.data.repository.Locales
import ru.timegrip.app.ui.activation.ActivationScreen
import ru.timegrip.app.ui.auth.AuthFlow
import ru.timegrip.app.ui.common.LocalAppContainer
import ru.timegrip.app.ui.main.MainScreen
import ru.timegrip.app.ui.update.UpdateDialog

/** Signed out → auth; signed in but not activated → activation; otherwise the app. */
@Composable
fun AppRoot() {
    val container = LocalAppContainer.current
    val session by container.sessionStore.state.collectAsStateWithLifecycle()
    val user = session.user

    // The UI language follows the account, also when it is changed on the web.
    LaunchedEffect(session.isSignedIn, user?.locale) {
        if (session.isSignedIn && user != null) Locales.apply(user.locale)
    }

    when {
        !session.isSignedIn || user == null -> AuthFlow(sessionExpired = session.expired && user != null)
        !user.isActive -> SignedInScope("activation") { ActivationScreen() }
        else -> SignedInScope("main") { MainScreen() }
    }
    UpdateDialog()
}

/**
 * The screens' ViewModels live as long as one sign-in: kept across a screen
 * rotation, dropped when the screen goes away otherwise (signing out). They
 * would otherwise carry the previous sign-in's data, such as a session list
 * already revoked, or filters naming another account's projects.
 */
@Composable
private fun SignedInScope(name: String, content: @Composable () -> Unit) {
    val scope: ViewModelScope = viewModel(key = "signed-in-$name")
    val activity = LocalActivity.current
    DisposableEffect(scope) {
        onDispose { if (activity?.isChangingConfigurations != true) scope.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides scope, content = content)
}

/** Holds the ViewModels of a [SignedInScope]; the activity keeps it across rotations. */
class ViewModelScope : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCleared() = viewModelStore.clear()
}
