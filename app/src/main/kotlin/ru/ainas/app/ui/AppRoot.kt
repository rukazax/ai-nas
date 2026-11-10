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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.compose.composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.ainas.app.auth.AuthState
import ru.ainas.app.auth.AuthStore
import kotlin.math.abs
import androidx.compose.ui.graphics.Color

/**
 * Корень приложения:
 * - нет аккаунта -> LandingScreen;
 * - есть -> NavHost: "home" (встроенный HorizontalPager из трёх WebView-страниц) и "files".
 */
@Composable
fun AppRoot(widthSizeClass: WindowWidthSizeClass) {
    val context = LocalContext.current
    val authFlow = remember(context) { AuthStore.authFlow(context) }
    val auth: AuthState? by authFlow.collectAsStateWithLifecycle(initialValue = null)

    val ready = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // DataStore выписывает состояние при первом чтении: ждём его, чтобы не мигнуть формой
        authFlow.first()
        ready.value = true
    }

    if (!ready.value) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Material3HostColor()),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomePager(
                auth = auth,
                onOpenFiles = { navController.navigate("files") },
            )
        }
        composable("files") {
            FilesScreen(
                widthSize = widthSizeClass,
                auth = auth ?: return@composable,
                onClose = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun Material3HostColor(): Color = MaterialTheme.colorScheme.background

/** Pager-оболочка: 3 страницы, скрытый edge-drawer, невидимые боковые кромки. */
@Composable
private fun HomePager(auth: AuthState?, onOpenFiles: () -> Unit) {
    if (auth == null) {
        LandingScreen()
        return
    }

    val pagerState = rememberPagerState(pageCount = { 3 })
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                DrawerScreen(
                    auth = auth,
                    onOpenFiles = {
                        scope.launch { drawerState.close() }
                        onOpenFiles()
                    },
                    onNavigatePage = { page ->
                        scope.launch {
                            drawerState.close()
                            pagerState.animateScrollToPage(page)
                        }
                    },
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Top,
            ) { page ->
                when (page) {
                    0 -> DashboardScreen()
                    1 -> DesktopScreen()
                    else -> DshScreen()
                }
            }

            PageDots(currentPage = pagerState.currentPage, modifier = Modifier.align(Alignment.TopCenter))

            EdgeNavStrip(
                isLeftEdge = true,
                currentPage = { pagerState.currentPage },
                pageCount = { pagerState.pageCount },
                onNavigate = { target -> scope.launch { pagerState.animateScrollToPage(target) } },
                onOpenDrawer = { scope.launch { drawerState.open() } },
                modifier = Modifier.align(Alignment.CenterStart),
            )
            EdgeNavStrip(
                isLeftEdge = false,
                currentPage = { pagerState.currentPage },
                pageCount = { pagerState.pageCount },
                onNavigate = { target -> scope.launch { pagerState.animateScrollToPage(target) } },
                onOpenDrawer = { },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
}

/** Три точки текущей страницы (без нижнего бара). */
@Composable
private fun PageDots(currentPage: Int, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.primary
    val idle = Color.White.copy(alpha = 0.55f)
    Row(
        modifier = modifier
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(if (index == currentPage) active else idle, CircleShape),
            )
        }
    }
}

/**
 * Невидимая кромка (28dp) поверх WebView:
 * лево: свайп вправо = drawer, свайп влево = следующая страница;
 * право: свайп влево = следующая страница, свайп вправо = назад.
 * Вертикальные жесты не перехватываем — WebView и пейджер живут сами.
 */
@Composable
private fun EdgeNavStrip(
    isLeftEdge: Boolean,
    currentPage: () -> Int,
    pageCount: () -> Int,
    onNavigate: (Int) -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .pointerInput(isLeftEdge) {
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
                        if (abs(traveled) > 48f) {
                            if (isLeftEdge && traveled > 0f) {
                                onOpenDrawer()
                            } else {
                                val direction = when {
                                    traveled > 0f -> -1 // свайп вправо - назад
                                    else -> 1           // свайп влево - следующая
                                }
                                val target = (currentPage() + direction).coerceIn(0, pageCount() - 1)
                                onNavigate(target)
                            }
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
