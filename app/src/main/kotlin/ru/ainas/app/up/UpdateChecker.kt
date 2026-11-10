package ru.ainas.app.up

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import ru.ainas.app.MainActivity
import ru.ainas.app.net.Http
import java.io.File
import java.io.IOException

@Serializable
data class ReleaseAsset(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
)

@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tagName: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
)

data class UpdateEntry(
    val tagName: String,
    val assetName: String,
    val assetUrl: String,
)

/**
 * Проверка версии по GitHub Releases (api.github.com/repos/rukazax/ai-nas/releases/latest)
 * и установка скачанного APK через PackageInstaller.
 */
object UpdateChecker {

    const val RELEASES_URL = "https://api.github.com/repos/rukazax/ai-nas/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    private val _pending = MutableStateFlow<UpdateEntry?>(null)
    /** Доступное обновление — читается UI (drawer) и App.onCreate. */
    val pending: StateFlow<UpdateEntry?> = _pending

    suspend fun check(currentVersion: String): UpdateEntry? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            Http.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val text = body.string()
                val info = json.decodeFromString(ReleaseInfo.serializer(), text)
                val tag = info.tagName.trim()
                if (tag.isEmpty() || !isNewer(tag, currentVersion)) return@withContext null
                val asset = info.assets.firstOrNull { candidate ->
                    candidate.name.endsWith(".apk") && candidate.url.isNotBlank()
                } ?: return@withContext null
                UpdateEntry(tagName = tag, assetName = asset.name, assetUrl = asset.url)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Сравнение semver-подобных тегов: 1.1.0 > 1.0.0; неизвестные суффиксы игнорируются. */
    fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = versionParts(remote)
        val currentParts = versionParts(current)
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun versionParts(tag: String): List<Int> {
        val head = tag.replace("v", "", ignoreCase = true)
            .substringBefore('-')
            .substringBefore('+')
        return head.split('.').map { part ->
            part.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    /** Фоновая проверка при старте приложения (App.onCreate запускает в корутине). */
    suspend fun backgroundCheck(context: Context) {
        val version = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
        val update = check(version)
        if (update != null) {
            _pending.value = update
        }
    }

    suspend fun downloadApk(update: UpdateEntry, context: Context, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "update")
            dir.mkdirs()
            val target = File(dir, update.assetName.ifBlank { "ai-nas-update.apk" })
            if (target.exists() && target.length() > 0L) {
                return@withContext target
            }
            val request = Request.Builder().url(update.assetUrl).get().build()
            Http.longClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("update download failed: HTTP " + resp.code)
                }
                val body = resp.body ?: throw IOException("update download failed: empty body")
                val total = body.contentLength()
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            onProgress(sent, total)
                        }
                        out.flush()
                    }
                }
            }
            target
        }

    /** Установка скачанного APK через PackageInstaller.session commit. */
    fun installApk(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session: PackageInstaller.Session = installer.openSession(sessionId)
        try {
            session.openWrite("ainas-apk", 0L, apk.length()).use { out ->
                apk.inputStream().use { input ->
                    input.copyTo(out)
                }
                out.flush()
            }
            session.commit(intentSender(context, sessionId))
        } finally {
            session.close()
        }
    }

    private fun intentSender(context: Context, sessionId: Int): IntentSender {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getActivity(context, sessionId, intent, flags)
        return pending.intentSender
    }
}
