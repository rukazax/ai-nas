package ru.ainas.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import kotlin.math.abs

/**
 * Корень приложения:
 * - нет аккаунта -> LandingScreen;
 * - есть -> один HorizontalPager из 5 страниц по кругу:
 *   Дашборд → Файлы → DSH → ubuntu → Настройки → (снова Дашборд).
 *
 * Тему применяет MainActivity (читает настройки) — здесь только фон корня:
 * Surface сверху перекрашивает всё окно, иначе в тёмной теме видны светлые
 * вспышки системного windowBackground (например, «Ищем фото и видео…»).
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val authFlow = remember(context) { AuthStore.authFlow(context) }
    val auth: AuthState? by authFlow.collectAsStateWithLifecycle(initialValue = null)
    val settings by remember(context) { SettingsStore.flow(context) }
        .collectAsStateWithLifecycle(initialValue = AppSettings())

    val background = MaterialTheme.colorScheme.background
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = background,
    ) {
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

    fun goTo(page: Int) {
        scope.launch {
            runCatching { pagerState.animateScrollToPage(page) }
        }
    }

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
                    onBackToDashboard = { goTo(0) },
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

        PageDots(currentPage = pagerState.currentPage, modifier = Modifier.align(Alignment.BottomCenter))

        // Свайп по кромке на ЛЮБОЙ странице: WebView-страницы (Дашборд/DSH/ubuntu)
        // съедают горизонтальные жесты, кромки шириной 34dp обеспечивают
        // предсказуемое перелистывание. По кругу: 0→4 и 4→0.
        WrapStrip(isNext = true, onWrap = { goTo(if (pagerState.currentPage + 1 > 4) 0 else pagerState.currentPage + 1) }, modifier = Modifier.align(Alignment.CenterEnd))
        WrapStrip(isNext = false, onWrap = { goTo(if (pagerState.currentPage - 1 < 0) 4 else pagerState.currentPage - 1) }, modifier = Modifier.align(Alignment.CenterStart))
    }

    // Системный «назад»: шаг влево по страницам; страница Файлов управляет собой сама.
    BackHandler(enabled = pagerState.currentPage > 0 && pagerState.currentPage != 1) {
        goTo(pagerState.currentPage - 1)
    }
}

/** Пять точек текущей страницы. */
@Composable
private fun PageDots(currentPage: Int, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Row(
        modifier = modifier
            .padding(bottom = 12.dp),
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
 * Невидимая кромка (34dp) для перелистывания: свайп от правого края влево — на
 * следующую страницу, свайп от левого края вправо — на предыдущую (по кругу).
 * [isNext] = полоса на правой кромке. Вертикальные жесты не перехватываются.
 */
@Composable
private fun WrapStrip(
    isNext: Boolean,
    onWrap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(34.dp)
            .pointerInput(isNext) {
                awaitEachGesture {
                    val down = awaitDown()
                    val pointerId = down.id
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var traveledX = 0f
                    var traveledY = 0f
                    var settled = false
                    while (!settled) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        traveledX += change.position.x - lastX
                        traveledY += change.position.y - lastY
                        lastX = change.position.x
                        lastY = change.position.y
                        if (abs(traveledX) > 48f && abs(traveledX) > abs(traveledY)) {
                            val forward = if (isNext) traveledX < 0f else traveledX > 0f
                            if (forward) onWrap()
                            settled = true
                        } else if (abs(traveledY) > 48f && abs(traveledY) > abs(traveledX)) {
                            settled = true // вертикальный скролл контента — уходим
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
