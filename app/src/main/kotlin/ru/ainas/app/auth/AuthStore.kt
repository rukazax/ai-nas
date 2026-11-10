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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.ainas.app.net.Http
import java.io.IOException

/** Файл DataStore намеренно объявлен на уровне файла — единственный инстанс в приложении. */
internal val Context.mainPrefsStore by preferencesDataStore(name = "ainas_main")

/** Публичное доменное имя NAS — работает и дома, и из интернета. */
const val DEFAULT_PUBLIC_HOST: String = "your_server.com"

/** Данные сессии: NAS + текущие basic-данные + опциональный DSH-токен. */
data class AuthState(
    val host: String,
    val user: String,
    val pass: String,
    val basic: String,
    val dshToken: String? = null,
    /** Локальный адрес NAS в домашней сети — для SFTP/SMB, когда host публичный. */
    val homeHost: String = "192.168.88.35",
) {
    fun userLine(): String = user + "@" + host

    /** Хосты для SFTP: публичный + домашний (файловый слой живёт на NAS, не на VPS). */
    fun sftpHosts(): List<String> =
        if (NasEndpoints.isLocalHost(host)) listOf(host)
        else listOf(host, homeHost).distinct()

    /** Для SMB берём сразу домашний адрес: порт 445 наружу не отдаётся. */
    fun smbHost(): String =
        if (NasEndpoints.isLocalHost(host)) host else homeHost

    /** Публичный домен для маршрутов, у которых нет локального аналога (DSH, noVNC).
     *  [override] — значение из настроек (пусто = автоматически). */
    fun publicHost(override: String? = null): String {
        val custom = override?.trim().orEmpty()
        if (custom.isNotEmpty()) return custom
        return if (NasEndpoints.isLocalHost(host)) DEFAULT_PUBLIC_HOST else host
    }

    /** Страница 0: дашборд NAS (локально — :8890, из интернета — /info/). */
    fun dashboardUrl(): String =
        if (NasEndpoints.isLocalHost(host)) "http://" + host + ":8890/"
        else "https://" + host + "/info/"

    /** Страница «ubuntu»: noVNC-рабочий стол сервера. */
    fun desktopUrl(publicHostOverride: String? = null): String =
        "https://" + publicHost(publicHostOverride) + "/ubuntu/"

    /** Страница «DSH» без токена. */
    fun dshBaseUrl(publicHostOverride: String? = null): String =
        "https://" + publicHost(publicHostOverride) + "/dsh/"
}

/** Кандидаты подключения и проверка живого сервера. */
object NasEndpoints {

    /** Локальные адреса: IPv4-частные сети, localhost, *.local (mDNS). */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim()
        if (h == "localhost" || h.endsWith(".local")) return true
        val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(h)
        if (m != null) {
            val a = m.groupValues[1].toIntOrNull() ?: return false
            val b = m.groupValues[2].toIntOrNull() ?: return false
            if (a == 10 || a == 127) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
        }
        return false
    }

    /**
     * По порядку пробуем маршруты, где сервер может жить:
     * 1) POST https://<host>/dsh/api/auth/login — DSH-API с pair-token;
     * 2) GET  https://<host>/info/ — публичный дашборд NAS (nginx на VPS);
     * 3) GET  https://<host>/dsh/ — DSH GUI;
     * 4) GET  http://<host>:8890/ — локальный дашборд NAS;
     * 5) GET  https://<host>/ и http://<host>/ — общий фолбэк.
     * Первый ответ с HTTP-статусом (даже 401/302) = сервер найден.
     */
    suspend fun probe(host: String, basic: String, json: String): ProbeResult =
        withContext(Dispatchers.IO) {
            var sawOpen = false   // хоть один маршрут ответил не-401/403
            var sawDenied = false // сервер жив, но все маршруты вернули 401/403

            // 1) DSH-API: единственный маршрут, где возможен токен
            try {
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://" + host + "/dsh/api/auth/login")
                    .header("Authorization", basic)
                    .post(body)
                    .build()
                Http.client.newCall(request).execute().use { resp ->
                    if (resp.code < 500) {
                        val text = resp.body?.string().orEmpty()
                        if (resp.code in 401..403) sawDenied = true else sawOpen = true
                        val token = extractTokenFrom(text)
                        // успешный логин с токеном — сразу возвращаем
                        if (resp.isSuccessful && token != null) {
                            return@withContext ProbeResult(true, token, denied = false)
                        }
                    }
                }
            } catch (_: IOException) {
                // маршрут недоступен — идём дальше
            }

            val gets = listOf(
                "https://" + host + "/info/",
                "https://" + host + "/dsh/",
                "http://" + host + ":8890/",
                "https://" + host + "/",
                "http://" + host + "/",
            )
            for (url in gets) {
                try {
                    val probe = Request.Builder()
                        .url(url)
                        .header("Authorization", basic)
                        .get()
                        .build()
                    Http.client.newCall(probe).execute().use { resp ->
                        if (resp.code in 401..403) sawDenied = true else sawOpen = true
                    }
                } catch (_: IOException) {
                    // пробуем следующий вариант
                }
            }
            ProbeResult(reachable = sawOpen || sawDenied, token = null, denied = !sawOpen && sawDenied)
        }

    private fun extractTokenFrom(text: String): String? {
        if (!text.contains("token")) return null
        return try {
            extractToken(Json.parseToJsonElement(text))
        } catch (_: Exception) {
            null
        }
    }
}

/** Результат проверки сервера: живой ли хост, выдал ли токен, запрещён ли доступ. */
data class ProbeResult(
    val reachable: Boolean,
    val token: String?,
    val denied: Boolean = false,
)

/** Сервер отвечает, но учётные данные отклонены (HTTP 401/403 на всех маршрутах). */
class AuthDeniedException(message: String) : Exception(message)

object AuthStore {

    private val KEY_HOST = stringPreferencesKey("auth/host")
    private val KEY_USER = stringPreferencesKey("auth/user")
    private val KEY_PASS = stringPreferencesKey("auth/pass")
    private val KEY_BASIC = stringPreferencesKey("auth/basic")
    private val KEY_TOKEN = stringPreferencesKey("auth/token")
    private val KEY_HOME_HOST = stringPreferencesKey("auth/home_host")

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
            homeHost = prefs[KEY_HOME_HOST]?.takeIf { it.isNotBlank() } ?: "192.168.88.35",
        )
    }

    suspend fun saveAuth(context: Context, auth: AuthState) {
        context.mainPrefsStore.edit { prefs ->
            prefs[KEY_HOST] = auth.host
            prefs[KEY_USER] = auth.user
            prefs[KEY_PASS] = auth.pass
            prefs[KEY_BASIC] = auth.basic
            if (auth.dshToken != null) prefs[KEY_TOKEN] = auth.dshToken else prefs.remove(KEY_TOKEN)
            if (auth.homeHost.isNotBlank()) prefs[KEY_HOME_HOST] = auth.homeHost
        }
    }

    /** Домашний адрес NAS для файлового слоя (SFTP/SMB). */
    suspend fun setHomeHost(context: Context, host: String) {
        context.mainPrefsStore.edit { prefs ->
            prefs[KEY_HOME_HOST] = host.trim()
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
     * Логин: нормализуем хост и перебираем известные маршруты сервера
     * (NasEndpoints.probe): DSH-API → публичный дашборд → DSH GUI →
     * локальный дашборд :8890 → корень. Если ни один не ответил — failure.
     */
    suspend fun login(context: Context, rawHost: String, user: String, pass: String): Result<AuthState> =
        withContext(Dispatchers.IO) {
            val host = normalizeHost(rawHost)
            val basic = Credentials.basic(user, pass)
            val body = json.encodeToString(
                LoginRequest.serializer(),
                LoginRequest(user = user, pass = pass),
            )

            val probe = NasEndpoints.probe(host, basic, body)
            if (!probe.reachable) {
                return@withContext Result.failure(
                    IOException("server unreachable: " + host),
                )
            }
            if (probe.denied && probe.token == null) {
                return@withContext Result.failure(
                    AuthDeniedException("unauthorized: " + host),
                )
            }

            val auth = AuthState(host = host, user = user, pass = pass, basic = basic, dshToken = probe.token)
            saveAuth(context, auth)
            Result.success(auth)
        }

    /** "https://your_server.com:443/" -> "your_server.com"; оставляем только хост. */
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
        if (colonIdx > 0) h = h.substring(0, colonIdx)
        return h
    }
}

internal fun extractToken(element: kotlinx.serialization.json.JsonElement): String? {
    if (element !is kotlinx.serialization.json.JsonObject) return null
    for ((key, value) in element) {
        if (key == "token" || key == "access_token" || key == "pair_token" || key == "jwt") {
            val v = value as? kotlinx.serialization.json.JsonPrimitive
            if (v != null && v.content.isNotBlank()) return v.content
        }
    }
    return null
}
