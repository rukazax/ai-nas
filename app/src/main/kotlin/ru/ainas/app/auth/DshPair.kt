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
            Http.client.newBuilder()
                .followRedirects(true)
                .build()
                .newCall(request)
                .execute()
                .use { resp ->
                    val finalUrl = resp.request.url.toString()
                    if (resp.isSuccessful && finalUrl.contains("token=")) finalUrl else null
                }
        } catch (_: IOException) {
            null
        }
    }

    /** Синхронный вариант для воркеров/фоновых потоков. */
    suspend fun fetchPairUrlSuspend(auth: AuthState, publicHostOverride: String? = null): String? =
        withContext(Dispatchers.IO) { fetchPairUrlBlocking(auth, publicHostOverride) }
}
