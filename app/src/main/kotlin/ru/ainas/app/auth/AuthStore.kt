package ru.ainas.app.auth

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.ainas.app.net.Http
import java.io.IOException

/** Файл DataStore намеренно объявлен на уровне файла — единственный инстанс в приложении. */
internal val Context.mainPrefsStore by preferencesDataStore(name = "ainas_main")

/** Данные сессии: NAS + текущие basic-данные + опциональный DSH-токен. */
data class AuthState(
    val host: String,
    val user: String,
    val pass: String,
    val basic: String,
    val dshToken: String? = null,
) {
    fun userLine(): String = user + "@" + host

    fun dshBaseUrl(): String = "https://" + host + "/dsh/"
}

object AuthStore {

    private val KEY_HOST = stringPreferencesKey("auth/host")
    private val KEY_USER = stringPreferencesKey("auth/user")
    private val KEY_PASS = stringPreferencesKey("auth/pass")
    private val KEY_BASIC = stringPreferencesKey("auth/basic")
    private val KEY_TOKEN = stringPreferencesKey("auth/token")

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class LoginRequest(val user: String, val pass: String)

    /** Поток текущей сессии; null — первый запуск, нужен LandingScreen. */
    fun authFlow(context: Context): Flow<AuthState?> =
        context.mainPrefsStore.data.map { prefs: Preferences -> fromPrefs(prefs) }
            .distinctUntilChanged()

    /** Синхронно-корутинное чтение текущего состояния (для Worker'ов). */
    suspend fun current(context: Context): AuthState? =
        fromPrefs(context.mainPrefsStore.data.map { it }.first())

    private fun fromPrefs(prefs: Preferences): AuthState? {
        val host = prefs[KEY_HOST]
        val user = prefs[KEY_USER]
        val pass = prefs[KEY_PASS]
        if (host.isNullOrBlank() || user.isNullOrBlank()) return null
        val basic = prefs[KEY_BASIC] ?: Credentials.basic(user, pass ?: "")
        val token = prefs[KEY_TOKEN]
        return AuthState(
            host = host,
            user = user,
            pass = pass ?: "",
            basic = basic,
            dshToken = token?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun saveAuth(context: Context, auth: AuthState) {
        context.mainPrefsStore.edit { prefs ->
            prefs[KEY_HOST] = auth.host
            prefs[KEY_USER] = auth.user
            prefs[KEY_PASS] = auth.pass
            prefs[KEY_BASIC] = auth.basic
            if (auth.dshToken != null) prefs[KEY_TOKEN] = auth.dshToken else prefs.remove(KEY_TOKEN)
        }
    }

    suspend fun updateToken(context: Context, token: String?) {
        context.mainPrefsStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(KEY_TOKEN) else prefs[KEY_TOKEN] = token
        }
    }

    suspend fun clearAuth(context: Context) {
        context.mainPrefsStore.edit { it.clear() }
    }

    /**
     * Логин по правилам ТЗ:
     * 1) POST https://<host>/dsh/api/auth/login — если отвечает OK и в теле есть token,
     *    сохраняем JWT-подобный pair-token вместе с basic;
     * 2) если эндпоинт не отвечает (NAS отдаёт только Basic-auth) — проверяем присутствие
     *    сервера простым HTTPS/HTTP-запросом и храним только Basic-заголовок;
     * 3) если сервер не достижим вовсе — failure.
     */
    suspend fun login(context: Context, rawHost: String, user: String, pass: String): Result<AuthState> =
        withContext(Dispatchers.IO) {
            val host = normalizeHost(rawHost)
            val basic = Credentials.basic(user, pass)
            var token: String? = null
            var reachable = false

            // 1) попытка JWT/pair-token на DSH-API
            try {
                val body = json.encodeToString(
                    LoginRequest.serializer(),
                    LoginRequest(user = user, pass = pass),
                ).toRequestBody("application/json".toMediaType())
                val request = okhttp3.Request.Builder()
                    .url("https://" + host + "/dsh/api/auth/login")
                    .header("Authorization", basic)
                    .post(body)
                    .build()
                Http.client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        reachable = true
                        val text = resp.body?.string().orEmpty()
                        if (text.contains("token")) {
                            try {
                                token = extractToken(Json.parseToJsonElement(text))
                            } catch (_: Exception) {
                                token = null
                            }
                        }
                    } else if (resp.code < 500) {
                        // живой сервер, но этот маршрут не тот — считаем Basic-only NAS
                        reachable = true
                        resp.body?.string()
                    }
                }
            } catch (_: IOException) {
                reachable = false
            }

            // 2) NAS отвечает только Basic: проверяем любой HTTP(S)-ответ от хоста
            if (!reachable) {
                for (scheme in listOf("https", "http")) {
                    try {
                        val probe = okhttp3.Request.Builder()
                            .url(scheme + "://" + host + "/")
                            .header("Authorization", basic)
                            .get()
                            .build()
                        Http.client.newCall(probe).execute().use { resp ->
                            if (resp.code > 0) reachable = true
                        }
                    } catch (_: IOException) {
                        // пробуем следующий вариант
                    }
                    if (reachable) break
                }
            }

            if (!reachable) {
                return@withContext Result.failure(
                    IOException("server unreachable: " + host)
                )
            }

            val auth = AuthState(host = host, user = user, pass = pass, basic = basic, dshToken = token)
            saveAuth(context, auth)
            Result.success(auth)
        }

    private fun extractToken(element: kotlinx.serialization.json.JsonElement): String? {
        if (element !is kotlinx.serialization.json.JsonObject) return null
        for ((key, value) in element) {
            if (key == "token" || key == "access_token" || key == "pair_token" || key == "jwt") {
                val v = value as? kotlinx.serialization.json.JsonPrimitive
                if (v != null && v.content.isNotBlank()) return v.content
            }
        }
        return null
    }

    /** "https://79e.ru:443/" -> "79e.ru"; оставляем только хост. */
    fun normalizeHost(host: String): String {
        var h = host.trim()
        for (scheme in listOf("https://", "http://")) {
            if (h.startsWith(scheme, ignoreCase = true)) {
                h = h.substring(scheme.length)
                break
            }
        }
        h = h.trimEnd('/')
        val slashIdx = h.indexOf('/')
        if (slashIdx > 0) h = h.substring(0, slashIdx)
        val colonIdx = h.lastIndexOf(':')
        if (colonIdx > 0 && !h.substring(colonIdx + 1).all { ch -> ch.isDigit() }) {
            h = h.substring(0, colonIdx)
        } else if (colonIdx > 0) {
            h = h.substring(0, colonIdx)
        }
        return h
    }
}
