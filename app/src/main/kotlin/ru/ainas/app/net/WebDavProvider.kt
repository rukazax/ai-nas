package ru.ainas.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV-провайдер на OkHttp + XmlPullParser (sardine заменён: Apache HttpClient
 * в sardine недоступен на Android 9+ — падал на AllowAllHostnameVerifier.INSTANCE).
 * Поддерживает Nextcloud-корень (https://host/remote.php/dav/files/<user>/) и любой другой DAV-коллекшн.
 */
class WebDavProvider(
    private val baseUrl: String,
    user: String,
    password: String,
) : Storage {

    /** preemptive Basic-аутентификация внутри каждого запроса */
    private val authHeader: String =
        okhttp3.Credentials.basic(user, password, charset = Charsets.UTF_8)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun urlFor(path: String, trailingSlash: Boolean): String {
        val cleanPath = if (path.isBlank() || path == "/") "" else path.trimEnd('/')
        val encoded = cleanPath.split("/")
            .filter { it.isNotBlank() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val base = baseUrl.trimEnd('/')
        return base + (if (encoded.isEmpty()) "" else "/" + encoded) + (if (trailingSlash) "/" else "")
    }

    private fun newReq(url: String, method: String, body: okhttp3.RequestBody? = null): Request.Builder =
        Request.Builder()
            .url(url.toHttpUrlOrNull() ?: throw IOException("WebDAV: адрес сервера неверный: " + baseUrl))
            .header("Authorization", authHeader)
            .method(method, body)

    private fun ioError(code: Int, what: String): IOException =
        IOException("WebDAV: HTTP " + code + " (" + what + ")")

    private fun code(resp: okhttp3.Response): Int {
        resp.use { return it.code }
    }

    override suspend fun list(path: String): List<StorageListEntry> = withContext(Dispatchers.IO) {
        val dir = if (path.isBlank() || path == "/") "/" else path
        val dirSelf = if (dir == "/") "" else dir.trimEnd('/')
        val root = parentPath(dir)
        val url = urlFor(dir, trailingSlash = true)
        val propfindBody = PROPFIND_XML.toRequestBody("application/xml".toMediaType())
        val resp = client.newCall(
            newReq(url, "PROPFIND", propfindBody)
                .header("Depth", "1")
                .build(),
        ).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw ioError(r.code, "не удалось открыть папку")
            val items = parseMultistatus(r.body!!.charStream(), dirSelf)
            items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })).let { list ->
                if (dir == "/") list else list.toMutableList().apply {
                    add(0, StorageListEntry(name = "..", path = root, isDir = true))
                }
            }
        }
    }

    /** Разбор 207 multistatus: чистый XmlPullParser без внешних зависимостей. */
    private fun parseMultistatus(stream: java.io.Reader, dirSelf: String): List<StorageListEntry> {
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream)
        val result = mutableListOf<StorageListEntry>()
        var href = ""
        var name: String? = null
        var size = 0L
        var mtime = 0L
        var isDir = false
        var inContent = false
        val sb = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "response" -> {
                        href = ""; name = null; size = 0; mtime = 0; isDir = false
                    }
                    "href" -> { inContent = true; sb.setLength(0) }
                    "displayname" -> { inContent = true; sb.setLength(0) }
                    "getcontentlength" -> { inContent = true; sb.setLength(0) }
                    "getlastmodified" -> { inContent = true; sb.setLength(0) }
                    "resourcetype" -> inContent = true
                    "collection" -> isDir = true
                }
                XmlPullParser.TEXT -> if (inContent) sb.append(parser.text)
                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "href" -> { href = sb.toString().trim(); inContent = false }
                    "displayname" -> { name = sb.toString().trim(); inContent = false }
                    "getcontentlength" -> { size = sb.toString().trim().toLongOrNull() ?: 0L; inContent = false }
                    "getlastmodified" -> {
                        mtime = parseHttpDate(sb.toString().trim())
                        inContent = false
                    }
                    "resourcetype" -> inContent = false
                    "response" -> {
                        val entryName = displayName(name, href)
                        if (entryName.isNotEmpty() && davPathOf(href) != dirSelf) {
                            result.add(
                                StorageListEntry(
                                    name = entryName,
                                    path = if (dirSelf.isBlank()) "/" + entryName else dirSelf + "/" + entryName,
                                    isDir = isDir,
                                    size = if (isDir) 0L else size,
                                    lastModified = mtime,
                                ),
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun local(tag: String): String = tag.substringAfterLast(':').lowercase()

    /** Из пути DAV берёт конечный сегмент (не декодируя целиком URL). */
    private fun davPathOf(href: String): String {
        val path = java.net.URI(
            if (href.startsWith("http")) href else "https://placeholder" + href,
        ).path ?: ""
        return path.trimEnd('/').let { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    private fun displayName(name: String?, href: String): String {
        if (!name.isNullOrBlank()) return name
        return href.trimEnd('/').substringAfterLast('/').let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }
    }

    private fun parseHttpDate(value: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    override suspend fun download(remotePath: String, target: File, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            if (!target.parentFile.exists()) target.parentFile.mkdirs()
            val url = urlFor(remotePath, trailingSlash = false)
            client.newCall(newReq(url, "GET").build()).execute().use { r ->
                if (!r.isSuccessful) throw ioError(r.code, "файл недоступен")
                val total = r.body?.contentLength() ?: -1L
                val buf = ByteArray(CHUNK)
                var sent = 0L
                r.body!!.byteStream().use { input ->
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
        }

    override suspend fun upload(local: File, remotePath: String, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val url = urlFor(remotePath, trailingSlash = false)
            val payloadBytes = local.length()
            local.inputStream().use { input ->
                val body = object : okhttp3.RequestBody() {
                    override fun contentType(): okhttp3.MediaType? = "application/octet-stream".toMediaType()
                    override fun contentLength(): Long = payloadBytes
                    override fun writeTo(sink: okio.BufferedSink) {
                        val buf = ByteArray(CHUNK)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            sent += n
                            onProgress(sent, payloadBytes)
                        }
                        sink.flush()
                    }
                }
                client.newCall(newReq(url, "PUT", body).build()).execute().use { r ->
                    if (!r.isSuccessful) throw ioError(r.code, "не удалось выгрузить файл")
                }
            }
        }

    /** Папку создаёт со всей иерархией по сегментам. */
    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        var cursor = ""
        for (segment in path.trimEnd('/').split("/")) {
            if (segment.isBlank()) continue
            cursor += "/" + segment.trimStart('/')
            val url = urlFor(cursor, trailingSlash = true)
            val resp = client.newCall(newReq(url, "MKCOL").build()).execute()
            if (!resp.isSuccessful) {
                if (code(resp) == 405) continue // уже существует
                throw ioError(code(resp), "не удалось создать папку")
            }
            resp.close()
        }
    }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        val from = urlFor(path, trailingSlash = false)
        val to = urlFor(joinPath(parentPath(path), newName), trailingSlash = false)
        val resp = client.newCall(
            newReq(from, "MOVE").header("Destination", to).header("Overwrite", "T").build(),
        ).execute()
        if (!resp.isSuccessful) {
            throw ioError(code(resp), "не удалось переименовать")
        }
        resp.close()
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val url = urlFor(path, trailingSlash = false)
        client.newCall(newReq(url, "DELETE").build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 404) throw ioError(r.code, "не удалось удалить")
        }
    }

    override fun close() {
        // OkHttp управляет пулом соединений сам
    }

    companion object {
        private const val CHUNK: Int = 32 * 1024

        private const val PROPFIND_XML =
            """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getlastmodified/>
  </d:prop>
</d:propfind>"""
    }
}
