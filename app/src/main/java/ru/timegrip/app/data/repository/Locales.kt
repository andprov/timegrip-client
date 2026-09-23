package ru.timegrip.app.data.repository

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.timegrip.app.domain.AppLocale
import java.util.Locale

/** The app language follows the account's `locale`, like the web app. */
object Locales {
    suspend fun apply(locale: AppLocale) = withContext(Dispatchers.Main) {
        val target = LocaleListCompat.forLanguageTags(locale.wire)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != target.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(target)
        }
    }

    /** The language the UI is shown in right now (before sign-in: the device's). */
    fun current(): AppLocale {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val language = if (appLocales.isEmpty) Locale.getDefault().language else appLocales[0]?.language
        return AppLocale.fromWire(language)
    }
}
