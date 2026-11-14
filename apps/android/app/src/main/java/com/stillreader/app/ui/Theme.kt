package com.stillreader.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

// Existing screens use these semantic colors, so every surface follows the theme.
val Paper: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
val Surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
val Forest: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val Ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onBackground
val Muted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Line: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
val FocusLetter: Color @Composable @ReadOnlyComposable get() = LocalFocusLetter.current

private val LightColors = lightColorScheme(
    primary = Color(0xFF304D3B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6D9CC), onPrimaryContainer = Color(0xFF304D3B),
    secondary = Color(0xFF304D3B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6D9CC), onSecondaryContainer = Color(0xFF304D3B),
    tertiary = Color(0xFF304D3B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6D9CC), onTertiaryContainer = Color(0xFF304D3B),
    background = Color(0xFFF6F5EF), onBackground = Color(0xFF29332B),
    surface = Color(0xFFFDFAF8), onSurface = Color(0xFF29332B), onSurfaceVariant = Color(0xFF70786B),
    surfaceVariant = Color(0xFFEAEDE4), surfaceTint = Color(0xFF304D3B),
    outline = Color(0xFFD6D9CC), outlineVariant = Color(0xFFD6D9CC),
    surfaceContainer = Color(0xFFF6F5EF), surfaceContainerLow = Color(0xFFFAF8F2),
    surfaceContainerLowest = Color(0xFFFDFAF8), surfaceContainerHigh = Color(0xFFEEEEE5),
    surfaceContainerHighest = Color(0xFFEAEDE4), surfaceBright = Color(0xFFFDFAF8), surfaceDim = Color(0xFFE0E2D9),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6C8A7), onPrimary = Color(0xFF172B1C),
    primaryContainer = Color(0xFF304D3B), onPrimaryContainer = Color(0xFFD3E9CE),
    secondary = Color(0xFFA6C8A7), onSecondary = Color(0xFF172B1C),
    secondaryContainer = Color(0xFF304D3B), onSecondaryContainer = Color(0xFFD3E9CE),
    tertiary = Color(0xFFA6C8A7), onTertiary = Color(0xFF172B1C),
    tertiaryContainer = Color(0xFF304D3B), onTertiaryContainer = Color(0xFFD3E9CE),
    background = Color(0xFF171D19), onBackground = Color(0xFFE7E9DF),
    surface = Color(0xFF222B24), onSurface = Color(0xFFE7E9DF), onSurfaceVariant = Color(0xFFAAB5A5),
    surfaceVariant = Color(0xFF354138), surfaceTint = Color(0xFFA6C8A7),
    outline = Color(0xFF83917F), outlineVariant = Color(0xFF3D4A40),
    surfaceContainer = Color(0xFF222B24), surfaceContainerLow = Color(0xFF1D251F),
    surfaceContainerLowest = Color(0xFF111612), surfaceContainerHigh = Color(0xFF2C362E),
    surfaceContainerHighest = Color(0xFF354138), surfaceBright = Color(0xFF354138), surfaceDim = Color(0xFF171D19),
    inverseSurface = Color(0xFFE7E9DF), inverseOnSurface = Color(0xFF29332B), inversePrimary = Color(0xFF304D3B),
)
private val LocalFocusLetter = staticCompositionLocalOf { Color(0xFFB32E24) }

internal enum class Appearance(val label: String, val preference: String) {
    SYSTEM("System", "system"), LIGHT("Light", "light"), DARK("Dark", "dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromPreference(value: String?): Appearance = entries.firstOrNull { it.preference == value } ?: SYSTEM
    }
}

internal data class AppearanceSettings(val appearance: Appearance, val select: (Appearance) -> Unit)
internal val LocalAppearance = staticCompositionLocalOf { AppearanceSettings(Appearance.SYSTEM) {} }

@Composable
fun StillTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("reading", Context.MODE_PRIVATE) }
    var appearance by remember(preferences) {
        mutableStateOf(Appearance.fromPreference(preferences.getString("appearance", null)))
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "appearance" || key == null) appearance = Appearance.fromPreference(prefs.getString("appearance", null))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = appearance.isDark(isSystemInDarkTheme())
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalFocusLetter provides if (dark) Color(0xFFF28B7D) else Color(0xFFB32E24),
            LocalAppearance provides AppearanceSettings(appearance) { selected ->
                appearance = selected
                preferences.edit { putString("appearance", selected.preference) }
            },
            content = content,
        )
    }
}
