package ru.timegrip.app

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.timegrip.app.ui.AppRoot
import ru.timegrip.app.ui.common.LocalAppContainer
import ru.timegrip.app.ui.theme.TimeGripTheme
import ru.timegrip.app.ui.theme.isDarkTheme

/**
 * AppCompat host: it provides the per-app language switch (AppCompatDelegate).
 * The manifest handles locale changes itself, so a language switch recomposes
 * the UI instead of recreating the activity (which made the screen blink).
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TimeGripApplication).container
        // Only when the app is opened: the process also starts for background sync.
        container.appUpdater.checkOnce()
        setContent {
            val theme by container.settingsStore.theme.collectAsStateWithLifecycle()
            val dark = isDarkTheme(theme, isSystemInDarkTheme())
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                TimeGripTheme(theme) {
                    AppRoot()
                }
            }
        }
    }
}
