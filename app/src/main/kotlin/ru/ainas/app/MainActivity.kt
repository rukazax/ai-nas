package ru.ainas.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.ainas.app.settings.AppSettings
import ru.ainas.app.settings.SettingsStore
import ru.ainas.app.settings.ThemeMode
import ru.ainas.app.ui.AiNasTheme
import ru.ainas.app.ui.AppRoot

/**
 * Главный Activity: edge-to-edge окно (insets не съедаются) + Compose-скелет.
 *
 * Тема задаётся ТОЛЬКО здесь, из настроек приложения: SYSTEM (следовать системе),
 * LIGHT или DARK. Раньше тема из AppRoot не применялась при смене режима в
 * запущенном процессе — теперь источник один и он на верхнем уровне композиции.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val settings by SettingsStore.flow(this)
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AiNasTheme(dark = dark) {
                AppRoot()
            }
        }
    }
}
