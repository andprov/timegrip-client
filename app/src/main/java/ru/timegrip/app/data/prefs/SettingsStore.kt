package ru.timegrip.app.data.prefs

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.timegrip.app.BuildConfig
import ru.timegrip.app.domain.ThemePreference

/** Device-only preferences: they are not part of the account on the server. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        prefs.getString(KEY_THEME, null)
            ?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
            ?: ThemePreference.SYSTEM,
    )
    val theme: StateFlow<ThemePreference> = _theme.asStateFlow()

    private val _apiBaseUrl = MutableStateFlow(prefs.getString(KEY_API_URL, null) ?: BuildConfig.DEFAULT_API_URL)
    val apiBaseUrl: StateFlow<String> = _apiBaseUrl.asStateFlow()

    fun setTheme(theme: ThemePreference) {
        prefs.edit { putString(KEY_THEME, theme.name) }
        _theme.value = theme
    }

    /** Returns false when [url] is not an http(s) URL. */
    fun setApiBaseUrl(url: String): Boolean {
        val trimmed = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        prefs.edit { putString(KEY_API_URL, parsed.toString()) }
        _apiBaseUrl.value = parsed.toString()
        return true
    }

    fun resetApiBaseUrl() {
        prefs.edit { remove(KEY_API_URL) }
        _apiBaseUrl.value = BuildConfig.DEFAULT_API_URL
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_API_URL = "api_base_url"
    }
}
