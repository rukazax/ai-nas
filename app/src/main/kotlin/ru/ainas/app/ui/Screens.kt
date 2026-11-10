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
import ru.ainas.app.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Пул WebView по ключу страницы: инстансы переживают смену страниц пейджера
 * (без перезагрузки при свайпах) и ставятся на паузу, когда страница не видна.
 */
private val webViewPool = LinkedHashMap<String, WebView>()

/** Тихий рестарт DSH-сессии не чаще, чем раз в 30 секунд. */
private var lastDshRefreshMs = 0L

/** Заголовки запроса, которые OkHttp/WebView проставляет сам — не пересылаем. */
private val IGNORED_REQ_HEADERS = setOf(
    "authorization", "cookie", "host", "content-length", "accept-encoding", "connection",
)

/** Заголовки ответа, которые нельзя копировать один-к-одному в WebResourceResponse. */
private val IGNORED_RESP_HEADERS = setOf(
    "transfer-encoding", "content-encoding", "content-length", "connection", "keep-alive",
)

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    pageKey: String,
    initialUrl: String,
    auth: AuthState,
    onSessionLost: () -> Unit,
): WebView = WebView(context).apply {
    // Хост NAS-сервера для под-ресурсов (прокси с basic, см. shouldInterceptRequest)
    val resourceHost = initialUrl.toHttpUrlOrNull()?.host ?: ""
    settings.javaScriptEnabled = true
    // CDP-отладка WebView (chrome://inspect на компьютере) + ручная диагностика
    android.webkit.WebView.setWebContentsDebuggingEnabled(true)
    // лог консоли браузера → logcat (диагностика пустых экранов SPA)
    setWebChromeClient(object : android.webkit.WebChromeClient() {
        override fun onConsoleMessage(console: android.webkit.ConsoleMessage?): Boolean {
            android.util.Log.i(
                "AiNasWeb",
                "console[" + (console?.messageLevel() ?: "") + "] " + (console?.message() ?: "") +
                    " @" + (console?.sourceId()?.substringAfterLast('/') ?: ""),
            )
            return true
        }
    })
    settings.domStorageEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.mediaPlaybackRequiresUserGesture = false
    settings.loadsImagesAutomatically = true
    webViewClient = object : WebViewClient() {
        /**
         * Прозрачный прокси одного хоста: nginx отдаёт статические ресурсы DSH
         * только с Basic-аутентификацией и НЕ принимает dsh-auth-cookie, а WebView
         * не пробрасывает задачу на ввод basic для под-ресурсов (CSS/JS/XHR) —
         * из-за этого DSH/noVNC тонут с пустым тёмным экраном. Запрашиваем сами через
         * OkHttp с заголовком Authorization и возвращаем результат в WebView.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: android.webkit.WebResourceRequest,
        ): android.webkit.WebResourceResponse? {
            val method = request.method.uppercase()
            if (request.url.host != resourceHost || (method != "GET" && method != "HEAD")) return null
            return try {
                val ok = Http.client.newBuilder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val headers = okhttp3.Headers.Builder().apply {
                    request.requestHeaders?.forEach { e ->
                        if (e.key.lowercase() !in IGNORED_REQ_HEADERS) add(e.key, e.value)
                    }
                }
                val newReqBuilder = okhttp3.Request.Builder()
                    .url(request.url.toString())
                    .headers(headers.build())
                    .header("Authorization", auth.basic)
                if (method == "HEAD") newReqBuilder.head() else newReqBuilder.get()
                val resp = ok.newCall(newReqBuilder.build()).execute()
                if (resp.code >= 400 || resp.body == null) {
                    resp.close()
                    return null // ошибку отдаём браузеру как есть
                }
                val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                val mime = contentType.split(';').firstOrNull() ?: "application/octet-stream"
                val charset = contentType.split(';').getOrNull(1)
                    ?.split('=')?.getOrNull(1)?.trim()
                val out = android.webkit.WebResourceResponse(mime, charset, resp.body!!.byteStream())
                return out
            } catch (_: Exception) {
                null // сеть ладит сама, отдаём дефолтное поведение браузера
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            android.util.Log.i("AiNasWeb", "finished: " + url)
            // Найдено через CDP-отладку: часть сборок WebView на HyperOS НЕ резолвит
            // height:100% у html/body (при innerHeight=904 считается 0px) — SPA DSH
            // и noVNC остаются нулевой высоты → пустой тёмный экран. Явная высота
            // в пикселях чинит отрисовку и для DSH, и для noVNC.
            view.evaluateJavascript(
                "(function(){var h=window.innerHeight;var s=document.getElementById('ainas-view-h')||document.createElement('style');s.id='ainas-view-h';s.textContent='html,body,#root{height:'+h+'px!important;min-height:0!important}';(document.head||document.documentElement).appendChild(s)})();",
                null,
            )
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
            // ВАЖНО: SPA DSH снимает ?token= из адресной строки сразу после старта —
            // это нормальная работа, а не потеря сессии. Раньше это приводило к циклу:
            // свежий токен → SPA сняла → onSessionLost → снова pair → снова сняла...
            // Потерей считаем только настоящий редирект на pair-страницу или логин.
            val lostSession = u.contains("/dsh/pair") || u.contains("/dsh/login")
            if (pageKey == "dsh" && lostSession) {
                val now = System.currentTimeMillis()
                if (now - lastDshRefreshMs > 10_000) {
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
    // Пул живёт дольше композиции: при изменении url (например, свежий pair-токен DSH)
    // перезагружаем тот же инстанс, иначе на экране останется старая страница.
    var loadedUrl by remember(pageKey) { mutableStateOf<String?>(null) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view
        },
        update = { wv ->
            if (isPageVisible) wv.onResume() else wv.onPause()
            if (loadedUrl != url) {
                loadedUrl = url
                wv.loadUrl(url)
            }
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
    // счётчик принудительного обновления: onSessionLost обнуляет url и пинает эффект
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(auth.basic, publicHostOverride, refresh) {
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
                // SPA сняла token из URL: запрашиваем свежий pair-токен и перезагружаем
                if (currentUrl.contains("token=")) {
                    url = null
                    refresh++
                }
            },
        )
    }
}
