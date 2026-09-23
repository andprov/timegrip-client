package ru.timegrip.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ru.timegrip.app.domain.ThemePreference

// The web app's palette (Tailwind indigo / gray / red) mapped onto Material 3 roles.
private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF312E81),
    secondary = Color(0xFF4B5563),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E7EB),
    onSecondaryContainer = Color(0xFF111827),
    tertiary = Color(0xFF0E7490),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    background = Color(0xFFF9FAFB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF9FAFB),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFEDEEF1),
    surfaceContainerHighest = Color(0xFFE5E7EB),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF9CA3AF),
    onSecondary = Color(0xFF111827),
    secondaryContainer = Color(0xFF374151),
    onSecondaryContainer = Color(0xFFF3F4F6),
    tertiary = Color(0xFF67E8F9),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    background = Color(0xFF030712),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF030712),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceContainerLowest = Color(0xFF030712),
    surfaceContainerLow = Color(0xFF111827),
    surfaceContainer = Color(0xFF151D2B),
    surfaceContainerHigh = Color(0xFF1F2937),
    surfaceContainerHighest = Color(0xFF273244),
    outline = Color(0xFF4B5563),
    outlineVariant = Color(0xFF1F2937),
)

/** Colors outside the Material roles: the running timer is red on the web too. */
@Immutable
data class ExtraColors(
    val running: Color,
    val onRunning: Color,
    val chartAccent: Color,
    val success: Color,
)

private val LightExtra = ExtraColors(
    running = Color(0xFFDC2626),
    onRunning = Color.White,
    chartAccent = Color(0xFF4F46E5),
    success = Color(0xFF15803D),
)

private val DarkExtra = ExtraColors(
    running = Color(0xFFF87171),
    onRunning = Color(0xFF450A0A),
    chartAccent = Color(0xFF818CF8),
    success = Color(0xFF4ADE80),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

@Composable
fun TimeGripTheme(theme: ThemePreference, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

fun isDarkTheme(theme: ThemePreference, systemDark: Boolean): Boolean = when (theme) {
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
    ThemePreference.SYSTEM -> systemDark
}
