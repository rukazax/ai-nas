package ru.ainas.app.net

import com.github.sardine.DavResource
import com.github.sardine.Sardine
import com.github.sardine.SardineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WebDAV-провайдер (Nextcloud-стиль: https://host/remote.php/dav/files/<user>/)
 * на sardine 5.10.
 */
class WebDavProvider(
    private val baseUrl: String,
    user: String,
    password: String,
) : Storage {

    /** корень коллекций, например /remote.php/dav/files/ka — по нему маппим пути провайдера */
    private val rootCollectionsPath: String = extractPath(baseUrl.trimEnd('/'))

    private val sardine: Sardine

    init {
        val host = extractHost(baseUrl)
        val s = SardineFactory.begin(user, password)
        if (host != null) {
            s.enablePreemptiveAuthentication(host)
        }
        sardine = s
    }

    private fun urlFor(path: String): String {
        val rel = if (path.isBlank() || path == "/") "" else path
        return baseUrl.trimEnd('/') + rel + "/"
    }

    private fun relativeOf(davPath: String): String {
        val trimmed = davPath.trimEnd('/')
        val idx = trimmed.indexOf(rootCollectionsPath)
        return if (idx < 0) trimmed else trimmed.substring(idx + rootCollectionsPath.length)
    }

    override suspend fun list(path: String): List<StorageListEntry> = withContext(Dispatchers.IO) {
        val dir = if (path.isBlank() || path == "/") "/" else path
        val root = parentPath(dir)
        val collection: List<DavResource> = sardine.list(urlFor(dir), 1)
        val items = collection.mapNotNull { r ->
            val name = r.name.trimEnd('/')
            if (name.isEmpty() || name == "..") return@mapNotNull null
            val rel = relativeOf(r.path)
            val relPath = if (rel.isBlank()) "/" else "/" + rel.trimEnd('/')
            if (relPath == dir.trimEnd('/').ifBlank { "/" } && relPath != "/") return@mapNotNull null
            if (relPath == "/") return@mapNotNull null
            val isDir = r.isDirectory
            StorageListEntry(
                name = name,
                path = joinPath(dir, name),
                isDir = isDir,
                size = r.contentLength ?: 0L,
                lastModified = r.modified?.time ?: 0L,
            )
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        if (dir == "/") items else items.toMutableList().apply {
            add(0, StorageListEntry(name = "..", path = root, isDir = true))
        }
    }

    override suspend fun download(remotePath: String, target: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            if (!target.parentFile.exists()) target.parentFile.mkdirs()
            val buf = ByteArray(CHUNK)
            var sent = 0L
            sardine.get(urlFor(remotePath).trimEnd('/')).use { input ->
                target.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent, -1L)
                    }
                    out.flush()
                    if (sent > 0) onProgress(sent, sent)
                }
            }
        }
    }

    override suspend fun upload(local: File, remotePath: String, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val payload = local.readBytes()
            sardine.put(urlFor(remotePath).trimEnd('/'), payload, null)
            onProgress(payload.size.toLong(), payload.size.toLong())
        }
    }

    override suspend fun mkdir(path: String) {
        withContext(Dispatchers.IO) {
            var cursor = ""
            for (segment in path.trimEnd('/').split("/")) {
                if (segment.isBlank()) continue
                cursor += "/" + segment.trimStart('/')
                val url = urlFor(cursor).trimEnd('/')
                if (!sardine.exists(url)) sardine.createDirectory(url)
            }
        }
    }

    override suspend fun rename(path: String, newName: String) {
        withContext(Dispatchers.IO) {
            sardine.move(
                urlFor(path).trimEnd('/'),
                urlFor(joinPath(parentPath(path), newName)).trimEnd('/'),
                false,
            )
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            sardine.delete(urlFor(path).trimEnd('/'))
        }
    }

    override fun close() {
        // sardine (Apache HttpClient) закрывает соединения по завершении запросов
    }

    private fun extractPath(url: String): String {
        return try {
            val u = java.net.URI(url)
            val p = u.rawPath ?: "/"
            if (p.endsWith("/")) p else p + "/"
        } catch (_: Exception) {
            "/"
        }
    }

    private fun extractHost(url: String): String? {
        return try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val CHUNK: Int = 32 * 1024
    }
}
