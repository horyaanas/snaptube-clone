package com.snaptube.clone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.snaptube.clone.data.prefs.AppPreferences

private val DarkColorScheme = darkColorScheme(
    primary = SnapYellow,
    onPrimary = Color.Black,
    primaryContainer = SnapYellowDark,
    onPrimaryContainer = Color.Black,
    secondary = SnapYellowLight,
    onSecondary = Color.Black,
    background = DarkBackground,
    onBackground = TextPrimaryDark,
    surface = DarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = DarkCard,
    onSurfaceVariant = TextSecondaryDark,
    outline = DarkBorder,
    error = SnapRed
)

private val LightColorScheme = lightColorScheme(
    primary = SnapYellow,
    onPrimary = Color.Black,
    primaryContainer = SnapYellowLight,
    onPrimaryContainer = TextPrimaryLight,
    secondary = SnapYellowDark,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = TextSecondaryLight,
    outline = LightBorder,
    error = SnapRed
)

@Composable
fun SnapTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/** Resolves the persisted theme mode into a Compose dark/light boolean. */
@Composable
fun resolveDarkTheme(mode: Int): Boolean = when (mode) {
    AppPreferences.THEME_LIGHT -> false
    AppPreferences.THEME_DARK -> true
    else -> isSystemInDarkTheme()
}
