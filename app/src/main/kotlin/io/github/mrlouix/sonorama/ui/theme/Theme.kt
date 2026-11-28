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
    primary = Color(0xFF003366),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF006A6A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB2F0F0),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = Color(0xFF7A5600),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFCC66),
    onTertiaryContainer = Color(0xFF261900),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE3EB),
    onSurfaceVariant = Color(0xFF42474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE0E2E9),
    outline = Color(0xFF696E76),
    outlineVariant = Color(0xFFC2C7CF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

// Sombre profond et peu lumineux, pensé pour la lecture du soir.
private val Dark = darkColorScheme(
    primary = Color(0xFFA5C8FF),
    onPrimary = Color(0xFF00305A),
    primaryContainer = Color(0xFF003366),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFF66CCCC),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F50),
    onSecondaryContainer = Color(0xFF9CF0F0),
    tertiary = Color(0xFFFFCC66),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFE0A3),
    background = Color(0xFF0E1318),
    onBackground = Color(0xFFE0E2E9),
    surface = Color(0xFF0E1318),
    onSurface = Color(0xFFE0E2E9),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    surfaceContainerLowest = Color(0xFF090D12),
    surfaceContainerLow = Color(0xFF161B21),
    surfaceContainer = Color(0xFF1A1F25),
    surfaceContainerHigh = Color(0xFF252A30),
    surfaceContainerHighest = Color(0xFF30353B),
    outline = Color(0xFF8C9199),
    outlineVariant = Color(0xFF42474E),
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
