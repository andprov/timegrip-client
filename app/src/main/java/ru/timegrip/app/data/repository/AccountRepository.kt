package ru.timegrip.app.data.repository

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import ru.timegrip.app.data.local.AppDatabase
import ru.timegrip.app.data.prefs.SessionStore
import ru.timegrip.app.data.remote.CodeBody
import ru.timegrip.app.data.remote.EmailUpdateBody
import ru.timegrip.app.data.remote.PasswordUpdateBody
import ru.timegrip.app.data.remote.TimeGripApi
import ru.timegrip.app.data.remote.apiCall
import ru.timegrip.app.data.sync.OpType
import ru.timegrip.app.data.sync.Outbox
import ru.timegrip.app.data.sync.SyncEngine
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.domain.AppLocale
import ru.timegrip.app.domain.Session
import ru.timegrip.app.domain.TimeFormat

/**
 * Account operations. Display preferences work offline through the outbox;
 * everything that needs the server's verdict (activation, password, email,
 * sessions) requires a connection.
 */
class AccountRepository(
    private val api: TimeGripApi,
    private val json: Json,
    private val db: AppDatabase,
    private val outbox: Outbox,
    private val sessionStore: SessionStore,
    private val syncEngine: SyncEngine,
    private val syncManager: SyncManager,
    private val authRepository: AuthRepository,
) {
    suspend fun setTimeFormat(timeFormat: TimeFormat) {
        val user = sessionStore.user ?: return
        db.withTransaction { outbox.userValueChanged(OpType.USER_TIME_FORMAT, timeFormat.wire) }
        sessionStore.saveUser(user.copy(timeFormat = timeFormat))
        syncManager.requestSync()
    }

    suspend fun setLocale(locale: AppLocale) {
        val user = sessionStore.user ?: return
        db.withTransaction { outbox.userValueChanged(OpType.USER_LOCALE, locale.wire) }
        sessionStore.saveUser(user.copy(locale = locale))
        Locales.apply(locale)
        syncManager.requestSync()
    }

    suspend fun refreshUser() = syncEngine.refreshUser()

    suspend fun activate(code: String) {
        apiCall(json) { api.activate(CodeBody(code.trim())) }
        refreshUser()
        syncManager.requestSync()
    }

    suspend fun resendActivationCode() {
        apiCall(json) { api.resendActivationCode() }
    }

    suspend fun resendCooldownSeconds(): Int = apiCall(json) { api.resendCooldown() }.retryAfterSeconds

    suspend fun changeEmail(password: String, newEmail: String) {
        val dto = apiCall(json) { api.updateEmail(EmailUpdateBody(password, newEmail.trim())) }
        syncEngine.storeUser(dto)
    }

    /** The server revokes the other sessions and issues new tokens for this one. */
    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val tokens = apiCall(json) { api.updatePassword(PasswordUpdateBody(currentPassword, newPassword)) }
        sessionStore.saveTokens(tokens.accessToken, tokens.refreshToken)
    }

    suspend fun sessions(): List<Session> =
        apiCall(json) { api.sessions() }.map { it.toDomain() }.sortedByDescending { it.createdAt }

    suspend fun revokeSession(id: Long) {
        apiCall(json) { api.revokeSession(id) }
    }

    suspend fun revokeAllSessions() {
        apiCall(json) { api.revokeAllSessions() }
        authRepository.signOut()
    }

    suspend fun deleteAccount() {
        apiCall(json) { api.deleteMe() }
        authRepository.signOut()
    }
}
