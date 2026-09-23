package ru.timegrip.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        !user.isActive -> ActivationScreen()
        else -> MainScreen()
    }
    UpdateDialog()
}
