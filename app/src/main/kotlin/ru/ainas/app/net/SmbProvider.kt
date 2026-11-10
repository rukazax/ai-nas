package ru.ainas.app.net

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * SMB2/3-провайдер для UNC-ресурса \\<host>\nas-data на jcifs-ng 2.1.7
 * (официальная координата: org.codelibs:jcifs:2.1.7).
 * tcp/445 используется jcifs-ng автоматически.
 */
class SmbProvider(
    host: String,
    private val share: String,
    user: String,
    password: String,
) : Storage {

    private val serverRoot = "smb://" + host.trim() + "/" + share.trim() + "/"

    // ВАЖНО: BaseContext создаётся лениво — его конструктор читает /etc/hosts и делает
    // DNS/NetBIOS-запрос (NameServiceClient.initCache), что даёт NetworkOnMainThreadException,
    // если вызвать его во время композиции. Все вызовы list/download/... идут с Dispatchers.IO.
    private val ctx: CIFSContext by lazy {
        // slf4j-simple уровень до инициализации байндинга (stderr → logcat "System.err")
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace")
        val props = Properties()
        props.setProperty("jcifs.smb.client.minVersion", "SMB202")
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
        // DFS выключен: не ждём DFS-рефералы и не падаем на их таймаутах
        props.setProperty("jcifs.smb.client.dfs.disabled", "true")
        // ВАЖНО: SPNEGO-парсинг jcifs требует org.bouncycastle.asn1.ASN1ApplicationSpecific,
        // удалённого из bcprov-jdk18on (bcprov-jdk15on исключён сборкой из-за конфликта
        // классов с sshj). NTLMSSP (useRawNTLM) — стандартный для SMB2/3+Samba путь,
        // BouncyCastle не нужен вовсе.
        props.setProperty("jcifs.smb.useRawNTLM", "true")
        val base = BaseContext(PropertyConfiguration(props))
        base.withCredentials(NtlmPasswordAuthenticator("", user, password))
    }

    /** путь каталога: "/dir/" (для listFiles/mkdir). */
    private fun dirUrl(path: String): String {
        val rel = path.trimEnd('/')
        return if (rel.isEmpty() || rel == "/") serverRoot else serverRoot + rel + "/"
    }

    /** путь файла: без завершающего слэша. */
    private fun fileUrl(path: String): String {
        val rel = path.trimEnd('/')
        return if (rel.isEmpty() || rel == "/") serverRoot.trimEnd('/') else serverRoot + rel
    }

    override suspend fun list(path: String): List<StorageListEntry> = withContext(Dispatchers.IO) {
        val dir = if (path.isBlank() || path == "/") "/" else path
        val root = parentPath(dir)
        val source = SmbFile(dirUrl(dir), ctx)
        val raw = source.listFiles()
        android.util.Log.i("AiNasSmb", "list dir=" + dir + " url=" + dirUrl(dir) + " raw=" + raw.size)
        val items = raw.mapNotNull { child ->
            val name = child.name.trimEnd('/')
            if (name.isEmpty() || name == "..") return@mapNotNull null
            val isDir = child.isDirectory
            StorageListEntry(
                name = name,
                path = joinPath(dir, name),
                isDir = isDir,
                size = if (isDir) 0L else child.length(),
                lastModified = child.lastModified(),
            )
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        if (dir == "/") items else items.toMutableList().apply {
            add(0, StorageListEntry(name = "..", path = root, isDir = true))
        }
    }

    override suspend fun download(remotePath: String, target: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val source = SmbFile(fileUrl(remotePath), ctx)
            if (!target.parentFile.exists()) target.parentFile.mkdirs()
            val total = source.length()
            val buf = ByteArray(CHUNK)
            var sent = 0L
            source.getInputStream().use { input ->
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

    override suspend fun upload(local: File, remotePath: String, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val target = SmbFile(fileUrl(remotePath), ctx)
            val total = local.length()
            val buf = ByteArray(CHUNK)
            var sent = 0L
            target.getOutputStream().use { out ->
                local.inputStream().use { input ->
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

    /** Папку создаёт со всей иерархией по сегментам. */
    override suspend fun mkdir(path: String) {
        withContext(Dispatchers.IO) {
            var cursor = ""
            for (segment in path.trimEnd('/').split("/")) {
                if (segment.isBlank() || segment == "/") continue
                cursor += "/" + segment.trimStart('/')
                val next = SmbFile(dirUrl(cursor), ctx)
                if (!next.exists()) next.mkdir()
            }
        }
    }

    override suspend fun rename(path: String, newName: String) {
        withContext(Dispatchers.IO) {
            val from = SmbFile(fileUrl(path), ctx)
            from.renameTo(SmbFile(fileUrl(joinPath(parentPath(path), newName)), ctx))
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            val entry = SmbFile(fileUrl(path), ctx)
            if (entry.isDirectory) {
                // каталог требует адрес со завершающим слэшем
                SmbFile(dirUrl(path), ctx).delete()
            } else {
                entry.delete()
            }
        }
    }

    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            SmbFile(fileUrl(path), ctx).exists()
        } catch (_: IOException) {
            false
        }
    }

    override fun close() {
        // jcifs-ng SmbFile не требует явных дескрипторов для блокирующих списков
    }

    companion object {
        private const val CHUNK: Int = 32 * 1024
    }
}
