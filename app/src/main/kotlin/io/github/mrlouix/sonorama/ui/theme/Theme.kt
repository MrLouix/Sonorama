package io.github.mrlouix.sonorama.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.mrlouix.sonorama.data.ThemeMode

// Les couples texte/fond ci-dessous respectent un contraste d'au moins 4,5:1 (WCAG AA).
private val Light = lightColorScheme(
    primary = Color(0xFF1F6A5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA9F0DD),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8DF),
    onSecondaryContainer = Color(0xFF06201A),
    tertiary = Color(0xFF7A5600),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261900),
    background = Color(0xFFF6FAF7),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF6FAF7),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F1),
    surfaceContainer = Color(0xFFEAEFEB),
    surfaceContainerHigh = Color(0xFFE4E9E6),
    surfaceContainerHighest = Color(0xFFDEE4E0),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

// Sombre profond et peu lumineux, pensé pour la lecture du soir.
private val Dark = darkColorScheme(
    primary = Color(0xFF8DD4C2),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF115044),
    onPrimaryContainer = Color(0xFFA9F0DD),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1C352F),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCDE8DF),
    tertiary = Color(0xFFF0BE6D),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFF0C1210),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0C1210),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFF070C0B),
    surfaceContainerLow = Color(0xFF141A18),
    surfaceContainer = Color(0xFF181E1C),
    surfaceContainerHigh = Color(0xFF222927),
    surfaceContainerHighest = Color(0xFF2D3431),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4945),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun SonoramaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
