package ru.ainas.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра «ночной ЦОД»: глубокий сине-графитовый фон + ледяной акцент.
private val AccentLight = Color(0xFF0B64D0)
private val AccentDark = Color(0xFF66AEE8)
private val AccentSoftDark = Color(0xFF9BD1FF)
private val SurfaceLight = Color(0xFFF6F8FC)
private val SurfaceDark = Color(0xFF0E1420)
private val SurfaceVariantLight = Color(0xFFE3EAF4)
private val SurfaceVariantDark = Color(0xFF1A2434)
private val OutlineLight = Color(0xFFB9C6D8)
private val OutlineDark = Color(0xFF3B4A60)

private val LightScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FF),
    onPrimaryContainer = Color(0xFF00244E),
    secondary = Color(0xFF3E5F8A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E6F8),
    onSecondaryContainer = Color(0xFF142B45),
    tertiary = Color(0xFF8A5A00),
    background = SurfaceLight,
    onBackground = Color(0xFF17202C),
    surface = SurfaceLight,
    onSurface = Color(0xFF17202C),
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF43505F),
    outline = OutlineLight,
    error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF002F63),
    primaryContainer = Color(0xFF0A4580),
    onPrimaryContainer = AccentSoftDark,
    secondary = Color(0xFFB0C8E8),
    onSecondary = Color(0xFF1B3350),
    secondaryContainer = Color(0xFF2C4468),
    onSecondaryContainer = Color(0xFFD9E6F8),
    tertiary = Color(0xFFFFB454),
    background = SurfaceDark,
    onBackground = Color(0xFFE4EBF5),
    surface = SurfaceDark,
    onSurface = Color(0xFFE4EBF5),
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFA9B8CC),
    outline = OutlineDark,
    error = Color(0xFFF2B8B5),
)

/**
 * Тема приложения: автоматически следует системной светлой/тёмной (Material You),
 * параметр [dark] оставлен для тестов и принудительных оверлеев.
 */
@Composable
fun AiNasTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
