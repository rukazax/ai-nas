package ru.ainas.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.ainas.app.auth.AuthState
import ru.ainas.app.auth.AuthStore
import ru.ainas.app.settings.AppSettings
import ru.ainas.app.settings.SettingsStore
import ru.ainas.app.settings.ThemeMode
import kotlin.math.abs
import androidx.compose.ui.graphics.Color

/**
 * Корень приложения:
 * - нет аккаунта -> LandingScreen;
 * - есть -> один HorizontalPager из 5 страниц по кругу:
 *   Дашборд → Файлы → DSH → ubuntu → Настройки → (снова Дашборд).
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val authFlow = remember(context) { AuthStore.authFlow(context) }
    val auth: AuthState? by authFlow.collectAsStateWithLifecycle(initialValue = null)
    val settings by remember(context) { SettingsStore.flow(context) }
        .collectAsStateWithLifecycle(initialValue = AppSettings())

    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    AiNasTheme(dark = dark) {
        if (auth == null) {
            LandingScreen()
        } else {
            HomePager(
                auth = auth!!,
                settings = settings,
            )
        }
    }
}

/** Пейджер-оболочка: 5 страниц, циклические свайпы по кромкам, точки страниц. */
@Composable
private fun HomePager(auth: AuthState, settings: AppSettings) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val scope = rememberCoroutineScope()
    val publicHost = settings.publicHost.takeIf { it.isNotBlank() }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            when (page) {
                0 -> DashboardScreen(auth = auth, isPageVisible = pagerState.currentPage == 0)
                1 -> FilesScreen(
                    auth = auth,
                    settings = settings,
                    onBackToDashboard = { scope.launch { pagerState.animateScrollToPage(0) } },
                )
                2 -> DshScreen(
                    auth = auth,
                    isPageVisible = pagerState.currentPage == 2,
                    publicHostOverride = publicHost,
                )
                3 -> DesktopScreen(
                    auth = auth,
                    isPageVisible = pagerState.currentPage == 3,
                    publicHostOverride = publicHost,
                )
                else -> SettingsScreen(auth = auth, settings = settings)
            }
        }

        PageDots(currentPage = pagerState.currentPage, modifier = Modifier.align(Alignment.TopCenter))

        // Циклические свайпы: с 1-й страницы вправо — на последнюю, с последней влево — на первую.
        if (pagerState.currentPage == 0) {
            WrapStrip(
                isLeft = true,
                onWrap = { scope.launch { pagerState.animateScrollToPage(4) } },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        if (pagerState.currentPage == 4) {
            WrapStrip(
                isLeft = false,
                onWrap = { scope.launch { pagerState.animateScrollToPage(0) } },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }

    // Системный «назад»: шаг влево по страницам; страница Файлов управляет собой сама.
    BackHandler(enabled = pagerState.currentPage > 0 && pagerState.currentPage != 1) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }
}

/** Пять точек текущей страницы. */
@Composable
private fun PageDots(currentPage: Int, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.primary
    val idle = Color.White.copy(alpha = 0.55f)
    Row(
        modifier = modifier
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(5) { index ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(if (index == currentPage) active else idle, CircleShape),
            )
        }
    }
}

/**
 * Невидимая кромка (32dp) для цикла: свайп от левого края вправо на первой
 * странице — к последней; свайп от правого края влево на последней — к первой.
 */
@Composable
private fun WrapStrip(
    isLeft: Boolean,
    onWrap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(32.dp)
            .pointerInput(isLeft) {
                awaitEachGesture {
                    val down = awaitDown()
                    val pointerId = down.id
                    var lastX = down.position.x
                    var traveled = 0f
                    var settled = false
                    while (!settled) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        val x = change.position.x
                        traveled += x - lastX
                        lastX = x
                        val wrap = if (isLeft) traveled > 48f else traveled < -48f
                        if (abs(traveled) > 48f) {
                            if (wrap) onWrap()
                            settled = true
                        }
                        if (change.changedToUp()) {
                            settled = true
                        }
                    }
                }
            },
    )
}

/** Стартовое касание для жеста потребуется всегда, потребление не требует. */
private suspend fun AwaitPointerEventScope.awaitDown() = awaitFirstDown(requireUnconsumed = false)
