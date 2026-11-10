package ru.ainas.app.ui

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.browser.customtabs.CustomTabsIntent
import ru.ainas.app.R
import ru.ainas.app.auth.AuthState

/** Универсальный WebView-экран (дашборд NAS / noVNC-рабочий стол / DSH).
 *  Если сервер запрашивает HTTP Basic (nginx auth_basic на /info/) —
 *  автоматически отвечаем учётными данными сессии. */
@Composable
fun NasWebView(url: String, auth: AuthState? = null, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpAuthRequest(
                        view: WebView,
                        handler: HttpAuthHandler,
                        host: String,
                        realm: String,
                    ) {
                        val a = auth
                        if (a != null && a.user.isNotBlank()) {
                            handler.proceed(a.user, a.pass)
                        } else {
                            handler.cancel()
                        }
                    }
                }
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(this, true)
                loadUrl(url)
            }
        },
        update = { view ->
            if (view.url == null) view.loadUrl(url)
        },
    )
}

/** Страница 0: дашборд NAS — локально :8890, из интернета /info/ (выбирает AuthState). */
@Composable
fun DashboardScreen(auth: AuthState) {
    NasWebView(url = auth.dashboardUrl(), auth = auth)
}

/** Страница 1: noVNC/Guacamole-десктоп сервера через браузер-канал. */
@Composable
fun DesktopScreen(auth: AuthState) {
    NasWebView(url = auth.desktopUrl(), auth = auth)
}

/** Страница 2: DSH. При отсутствии cookie — кнопка сопряжения через Chrome Custom Tabs. */
@Composable
fun DshScreen(auth: AuthState) {
    val context = LocalContext.current
    var pairPrompt by remember { mutableStateOf(hasDshCookie(auth)) }

    if (!pairPrompt) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = 160.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxHeight(0.45f), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = stringResource(R.string.dsh_need_pair),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            TextButton(onClick = { openDshPair(context, auth) }) {
                Text(text = stringResource(R.string.dsh_login_button), color = Color.White)
            }
            TextButton(onClick = { pairPrompt = hasDshCookie(auth) }) {
                Text(text = stringResource(R.string.dsh_refresh))
            }
        }
    } else {
        NasWebView(url = auth.dshBaseUrl(), auth = auth)
    }
}

private fun hasDshCookie(auth: AuthState): Boolean =
    CookieManager.getInstance().getCookie("https://" + auth.publicHost()) == null

private fun openDshPair(context: android.content.Context, auth: AuthState) {
    val target = Uri.parse("https://" + auth.publicHost() + "/dsh/pair")
    try {
        val customTabs = CustomTabsIntent.Builder().build()
        customTabs.launchUrl(context, target)
    } catch (_: Exception) {
        // Chrome Custom Tabs недоступен — обычный браузер
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, target))
        } catch (_: Exception) {
        }
    }
}
