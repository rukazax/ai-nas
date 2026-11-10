package ru.ainas.app.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Единый HTTP-клиент приложения (OkHttp 4):
 * - общий cookie-jar для DSH-авторизации (WebView и Chrome Custom Tab живут в своём
 *   cookie-хранилище, а API-запросы приложения ходят через этот jar);
 * - короткий таймаут подключения для перк-проверок локальной NAS.
 */
object Http {

    class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) store[cookieKey(c)] = c
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val matched = ArrayList<Cookie>()
            for (c in store.values) if (c.matches(url)) matched.add(c)
            return matched
        }

        fun clear() = store.clear()

        private fun cookieKey(c: Cookie): String = c.domain + "|" + c.path + "|" + c.name
    }

    val cookieJar: MemoryCookieJar = MemoryCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .writeTimeout(30L, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    /** Клиент для больших передач (SFTP/DAV обходятся без него, GitHub-обновление — да). */
    val longClient: OkHttpClient = client.newBuilder()
        .readTimeout(0L, TimeUnit.MILLISECONDS)
        .writeTimeout(0L, TimeUnit.MILLISECONDS)
        .build()
}
