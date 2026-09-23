package ru.timegrip.app.data.prefs

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.timegrip.app.domain.AppLocale
import ru.timegrip.app.domain.TimeFormat
import ru.timegrip.app.domain.User

data class SessionState(
    val accessToken: String?,
    val refreshToken: String?,
    val user: User?,
    /** The refresh token was rejected: the user must sign in again, local data is kept. */
    val expired: Boolean,
) {
    val isSignedIn: Boolean get() = accessToken != null && user != null && !expired
}

/**
 * Tokens and the cached profile. Reads are synchronous because OkHttp's
 * interceptors run on its own threads and need the token immediately.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val accessToken: String? get() = _state.value.accessToken
    val refreshToken: String? get() = _state.value.refreshToken
    val user: User? get() = _state.value.user

    /** Whose projects and timers the local database holds. */
    val ownerUserId: String? get() = prefs.getString(KEY_OWNER, null)

    /** The whole history has been downloaded once for the current owner. */
    var fullSyncDone: Boolean
        get() = prefs.getBoolean(KEY_FULL_SYNC, false)
        set(value) = prefs.edit { putBoolean(KEY_FULL_SYNC, value) }

    @Synchronized
    fun saveTokens(access: String, refresh: String) {
        prefs.edit {
            putString(KEY_ACCESS, access)
            putString(KEY_REFRESH, refresh)
            putBoolean(KEY_EXPIRED, false)
        }
        _state.update { it.copy(accessToken = access, refreshToken = refresh, expired = false) }
    }

    @Synchronized
    fun saveUser(user: User) {
        prefs.edit { putString(KEY_USER, json.encodeToString(StoredUser.serializer(), StoredUser.from(user))) }
        _state.update { it.copy(user = user) }
    }

    @Synchronized
    fun setOwner(userId: String) {
        prefs.edit {
            putString(KEY_OWNER, userId)
            putBoolean(KEY_FULL_SYNC, false)
        }
    }

    @Synchronized
    fun markExpired() {
        prefs.edit {
            remove(KEY_ACCESS)
            remove(KEY_REFRESH)
            putBoolean(KEY_EXPIRED, true)
        }
        _state.update { it.copy(accessToken = null, refreshToken = null, expired = true) }
    }

    /** Forgets the account entirely (explicit sign-out or account deletion). */
    @Synchronized
    fun clear() {
        prefs.edit { clear() }
        _state.value = SessionState(null, null, null, expired = false)
    }

    private fun load(): SessionState = SessionState(
        accessToken = prefs.getString(KEY_ACCESS, null),
        refreshToken = prefs.getString(KEY_REFRESH, null),
        user = prefs.getString(KEY_USER, null)?.let { stored ->
            runCatching { json.decodeFromString(StoredUser.serializer(), stored).toUser() }.getOrNull()
        },
        expired = prefs.getBoolean(KEY_EXPIRED, false),
    )

    @Serializable
    private data class StoredUser(
        val id: String,
        val email: String,
        val isActive: Boolean,
        val timeFormat: String,
        val locale: String,
    ) {
        fun toUser() = User(id, email, isActive, TimeFormat.fromWire(timeFormat), AppLocale.fromWire(locale))

        companion object {
            fun from(user: User) =
                StoredUser(user.id, user.email, user.isActive, user.timeFormat.wire, user.locale.wire)
        }
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER = "user"
        const val KEY_EXPIRED = "expired"
        const val KEY_OWNER = "owner_user_id"
        const val KEY_FULL_SYNC = "full_sync_done"
    }
}
