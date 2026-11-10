package ru.ainas.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import ru.ainas.app.net.Http
import java.io.IOException

/**
 * Получение свежего pair-URL для DSH GUI:
 * GET https://<host>/dsh/pair с basic-auth -> 302 на /dsh/?token=…
 * OkHttp следует редиректам автоматически, поэтому берём финальный URL.
 */
object DshPair {

    fun fetchPairUrl(auth: AuthState, publicHostOverride: String? = null): String? =
        fetchPairUrlBlocking(auth, publicHostOverride)

    private fun fetchPairUrlBlocking(auth: AuthState, hostOverride: String?): String? {
        val host = hostOverride?.takeIf { it.isNotBlank() } ?: auth.publicHost()
        return try {
            val request = Request.Builder()
                .url("https://" + host + "/dsh/pair")
                .header("Authorization", auth.basic)
                .get()
                .build()
            // ВАЖНО: редирект не прослеживаем автоматически. OkHttp сохраняет заголовок
            // Authorization при редиректе внутри одного хоста, а /dsh/?token=… отвечает
            // на чужой Basic 401 — из-за этого pair-флоу падал и грузился /dsh/ без токена.
            Http.client.newBuilder()
                .followRedirects(false)
                .build()
                .newCall(request)
                .execute()
                .use { resp ->
                    val loc = if (resp.isRedirect) resp.header("Location") else null
                    when {
                        loc != null && loc.contains("token=") ->
                            if (loc.startsWith("http")) loc
                            else "https://" + host + (if (loc.startsWith("/")) "" else "/") + loc
                        resp.isSuccessful && resp.request.url.toString().contains("token=") ->
                            resp.request.url.toString()
                        else -> null
                    }
                }
        } catch (_: IOException) {
            null
        }
    }

    /** Синхронный вариант для воркеров/фоновых потоков. */
    suspend fun fetchPairUrlSuspend(auth: AuthState, publicHostOverride: String? = null): String? =
        withContext(Dispatchers.IO) {
            val u = fetchPairUrlBlocking(auth, publicHostOverride)
            android.util.Log.i("AiNasPair", "pair url = " + (u ?: "null"))
            u
        }
}
