package ru.ainas.app.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ru.ainas.app.auth.AuthState
import ru.ainas.app.auth.DshPair

/**
 * Пул WebView по ключу страницы: инстансы переживают смену страниц пейджера
 * (без перезагрузки при свайпах) и ставятся на паузу, когда страница не видна.
 */
private val webViewPool = LinkedHashMap<String, WebView>()

/** Тихий рестарт DSH-сессии не чаще, чем раз в 30 секунд. */
private var lastDshRefreshMs = 0L

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    pageKey: String,
    initialUrl: String,
    auth: AuthState,
    onSessionLost: () -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.mediaPlaybackRequiresUserGesture = false
    settings.loadsImagesAutomatically = true
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            android.util.Log.i("AiNasWeb", "finished: " + url)
        }

        override fun onReceivedError(view: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
            android.util.Log.w("AiNasWeb", "error: " + req.url + " " + err.description)
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String,
        ) {
            android.util.Log.i("AiNasWeb", "httpAuth: " + host)
            if (auth.user.isNotBlank()) handler.proceed(auth.user, auth.pass) else handler.cancel()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            val u = url ?: return
            val lostSession = u.contains("/dsh/pair") || u.contains("/dsh/login") ||
                (pageKey == "dsh" && u.endsWith("/dsh/") && !u.contains("token="))
            if (pageKey == "dsh" && lostSession) {
                val now = System.currentTimeMillis()
                if (now - lastDshRefreshMs > 30_000) {
                    lastDshRefreshMs = now
                    onSessionLost()
                }
            }
        }
    }
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(this, true)
    android.util.Log.i("AiNasWeb", "create " + pageKey + " -> " + initialUrl)
    loadUrl(initialUrl)
}

/**
 * WebView-страница: пул по [pageKey], авто-basic-auth, пауза вне экрана.
 * [onSessionLost] — сайт вернул на логин (токен DSH истёк): просим свежий URL.
 */
@Composable
fun NasWebView(
    pageKey: String,
    url: String,
    auth: AuthState,
    isPageVisible: Boolean = true,
    onSessionLost: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = remember(pageKey) {
        webViewPool.getOrPut(pageKey) {
            createWebView(context, pageKey, url, auth, onSessionLost)
        }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view
        },
        update = { wv ->
            if (isPageVisible) wv.onResume() else wv.onPause()
        },
    )
    DisposableEffect(pageKey) {
        onDispose { view.onPause() }
    }
}

/** Страница «Дашборд»: локально :8890, из интернета /info/. */
@Composable
fun DashboardScreen(
    auth: AuthState,
    isPageVisible: Boolean,
) {
    NasWebView(
        pageKey = "dashboard",
        url = auth.dashboardUrl(),
        auth = auth,
        isPageVisible = isPageVisible,
    )
}

/** Страница «ubuntu» (noVNC): basic-auth отдаёт WebView автоматически. */
@Composable
fun DesktopScreen(
    auth: AuthState,
    isPageVisible: Boolean,
    publicHostOverride: String?,
) {
    NasWebView(
        pageKey = "desktop",
        url = auth.desktopUrl(publicHostOverride),
        auth = auth,
        isPageVisible = isPageVisible,
    )
}

/**
 * Страница «DSH»: при входе и при каждой потере сессии получаем свежий
 * pair-токен (GET /dsh/pair с basic-auth → 302 на /dsh/?token=…) и грузим его.
 */
@Composable
fun DshScreen(
    auth: AuthState,
    isPageVisible: Boolean,
    publicHostOverride: String?,
) {
    var url by rememberSaveable(auth.basic, publicHostOverride) { mutableStateOf<String?>(null) }

    LaunchedEffect(auth.basic, publicHostOverride) {
        val u: String? = url
        if (u == null || !u.contains("token=")) {
            url = DshPair.fetchPairUrlSuspend(auth, publicHostOverride)
                ?: auth.dshBaseUrl(publicHostOverride)
        }
    }

    val currentUrl = url
    if (currentUrl == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    } else {
        NasWebView(
            pageKey = "dsh",
            url = currentUrl,
            auth = auth,
            isPageVisible = isPageVisible,
            onSessionLost = {
                // потеря сессии: сбрасываем URL — LaunchedEffect получит свежий токен
                if (currentUrl.contains("token=")) url = null
            },
        )
    }
}
