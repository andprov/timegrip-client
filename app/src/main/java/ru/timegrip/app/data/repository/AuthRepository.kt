package ru.timegrip.app.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import ru.timegrip.app.data.local.AppDatabase
import ru.timegrip.app.data.prefs.SessionState
import ru.timegrip.app.data.prefs.SessionStore
import ru.timegrip.app.data.remote.EmailBody
import ru.timegrip.app.data.remote.RefreshBody
import ru.timegrip.app.data.remote.ResetPasswordBody
import ru.timegrip.app.data.remote.SignInBody
import ru.timegrip.app.data.remote.SignUpBody
import ru.timegrip.app.data.remote.TimeGripApi
import ru.timegrip.app.data.remote.apiCall
import ru.timegrip.app.data.sync.SyncEngine
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.data.sync.SyncWorker
import ru.timegrip.app.domain.AppLocale

class AuthRepository(
    private val context: Context,
    private val api: TimeGripApi,
    private val json: Json,
    private val db: AppDatabase,
    private val sessionStore: SessionStore,
    private val syncEngine: SyncEngine,
    private val syncManager: SyncManager,
) {
    val session: StateFlow<SessionState> = sessionStore.state

    suspend fun signIn(email: String, password: String) {
        val tokens = apiCall(json) { api.signIn(SignInBody(email.trim(), password)) }
        val user = apiCall(json) { api.me("Bearer ${tokens.accessToken}") }.toDomain()

        // Local data belongs to one account. Signing back into the same one
        // after an expired session keeps changes that were not uploaded yet.
        if (sessionStore.ownerUserId != user.id) {
            wipeLocalData()
            sessionStore.setOwner(user.id)
        }
        // The profile goes first so the app never shows a signed-in state
        // with the previous account's profile.
        sessionStore.saveUser(user)
        sessionStore.saveTokens(tokens.accessToken, tokens.refreshToken)
        syncEngine.storeUser(apiCall(json) { api.me() })
        sessionStore.user?.let { Locales.apply(it.locale) }

        SyncWorker.schedulePeriodic(context)
        syncManager.requestSync()
    }

    suspend fun signUp(email: String, password: String, locale: AppLocale) {
        apiCall(json) { api.signUp(SignUpBody(email.trim(), password, locale.wire)) }
        signIn(email, password)
    }

    suspend fun forgotPassword(email: String) {
        apiCall(json) { api.forgotPassword(EmailBody(email.trim())) }
    }

    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        apiCall(json) { api.resetPassword(ResetPasswordBody(email.trim(), code.trim(), newPassword)) }
    }

    /** Changes that exist only on this device and would be lost by signing out. */
    suspend fun unsyncedChangesCount(): Int = db.outboxDao().count()

    /** Signs out and forgets everything stored for the account on this device. */
    suspend fun signOut() {
        sessionStore.refreshToken?.let { refreshToken ->
            withTimeoutOrNull(5_000) {
                runCatching { apiCall(json) { api.logout(RefreshBody(refreshToken)) } }
            }
        }
        SyncWorker.cancelAll(context)
        sessionStore.clear()
        wipeLocalData()
        syncManager.clearState()
    }

    private suspend fun wipeLocalData() = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }
}
