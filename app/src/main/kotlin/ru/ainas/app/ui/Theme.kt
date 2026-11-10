package ru.ainas.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AccentLight = Color(0xFF0B64D0)
private val AccentDark = Color(0xFF66AEE8)
private val SurfaceLight = Color(0xFFF6F8FC)
private val SurfaceDark = Color(0xFF0E1420)

/** Минималистичная тема Material 3 на базе системного светлого/тёмного оформления. */
@Composable
fun AiNasTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = AccentDark,
            background = SurfaceDark,
            surface = SurfaceDark,
        )
    } else {
        lightColorScheme(
            primary = AccentLight,
            background = SurfaceLight,
            surface = SurfaceLight,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
