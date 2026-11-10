package ru.ainas.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.view.WindowCompat
import ru.ainas.app.ui.AiNasTheme
import ru.ainas.app.ui.AppRoot

/**
 * Главный Activity: edge-to-edge окно (insets не съедаются) + Compose-скелет.
 */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val windowSize = calculateWindowSizeClass(this)
            AiNasTheme {
                AppRoot(widthSizeClass = windowSize.widthSizeClass)
            }
        }
    }
}
