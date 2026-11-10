package ru.ainas.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.Security
import java.util.EnumSet

/**
 * SFTP-провайдер на sshj 0.38.0 (+ BouncyCastle).
 * Держит одно SSH-соединение и лениво переподключается при обрыве.
 * ВАЖНО: передачи крупных файлов идут через RemoteFile (SFTP READ/WRITE-блоки),
 * поэтому умеет точный прогресс и стрим ContentResolver без копий.
 */
class SftpProvider(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val user: String,
    private val password: String,
    /** Резервный адрес (домашний IP), когда основной хост не отвечает по SFTP. */
    private val altHost: String? = null,
) : Storage {

    @Volatile
    private var activeHost: String = host

    @Volatile
    private var ssh: SSHClient? = null

    @Volatile
    private var sftp: SFTPClient? = null

    private suspend fun <T> useSession(block: (SFTPClient) -> T): T = withContext(Dispatchers.IO) {
        // ВАЖНО: любые сетевые операции sshj — только вне главного потока
        val first = ensure()
        return@withContext try {
            block(first)
        } catch (e: Exception) {
            // обрыв транспорта -> попытка с чистого соединения
            close()
            block(ensure())
        }
    }

    private suspend fun ensure(): SFTPClient = withContext(Dispatchers.IO) {
        val current = sftp
        val client = ssh
        if (current != null && client != null && client.isConnected) {
            return@withContext current
        }
        Security.addProvider(BouncyCastleProvider())
        var lastError: Exception? = null
        for (candidate in listOf(host, altHost).filterNotNull().distinct()) {
            try {
                val fresh = SSHClient()
                fresh.addHostKeyVerifier(PromiscuousVerifier())
                fresh.timeout = 15000          // SO_TIMEOUT: операции не висят вечно
                fresh.connectTimeout = 10000
                fresh.connect(candidate, port)
                fresh.authPassword(user, password)
                val channel = fresh.newSFTPClient()
                activeHost = candidate
                ssh = fresh
                sftp = channel
                return@withContext channel
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("SFTP connect failed: " + host)
    }

    override suspend fun list(path: String): List<StorageListEntry> {
        val dir = if (path.isBlank() || path == "/") "/" else path
        val root = parentPath(dir)
        return useSession { c ->
            val raw = c.ls(dir).toList()
            val items = raw.map { info ->
                val isDir = info.isDirectory
                val size = if (isDir) 0L else info.attributes.size
                StorageListEntry(
                    name = info.name,
                    path = joinPath(dir, info.name),
                    isDir = isDir,
                    size = size,
                    lastModified = 0L,
                )
            }
            if (dir == "/") items else items.sortedBy { if (it.isDir) 0 else 1 }.toMutableList().apply {
                add(0, StorageListEntry(name = "..", path = root, isDir = true))
            }
        }
    }

    override suspend fun download(remotePath: String, target: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val c = ensure()
            val attrs = c.lstat(remotePath)
            val total = attrs.size
            if (!target.parentFile.exists()) target.parentFile.mkdirs()
            val rf: RemoteFile = c.open(remotePath, EnumSet.of(OpenMode.READ))
            try {
                val buf = ByteArray(CHUNK)
                var offset = 0L
                target.outputStream().use { out ->
                    while (true) {
                        val n = rf.read(offset, buf, 0, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        offset += n
                        onProgress(offset, total)
                    }
                    out.flush()
                }
            } finally {
                rf.close()
            }
        }
    }

    /** Upload из обычного файла. */
    override suspend fun upload(local: File, remotePath: String, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            local.inputStream().use { input ->
                putStreamInternal(input, local.length(), remotePath, onProgress)
            }
        }
    }

    /** Upload потока (например, из ContentResolver при автозагрузке фото). */
    suspend fun putStream(input: InputStream, contentLength: Long, remotePath: String): Boolean {
        withContext(Dispatchers.IO) {
            putStreamInternal(input, contentLength, remotePath) { _, _ -> }
        }
        return true
    }

    private suspend fun putStreamInternal(
        input: InputStream,
        contentLength: Long,
        remotePath: String,
        onProgress: (Long, Long) -> Unit,
    ) {
        val c = ensure()
        // каталоги пути могут не существовать
        val parent = parentPath(remotePath)
        if (parent != "/") {
            var cursor = ""
            for (segment in parent.trimEnd('/').split("/")) {
                if (segment.isBlank()) continue
                cursor += "/" + segment
                try {
                    c.mkdir(cursor)
                } catch (_: IOException) {
                    // каталог уже существует
                }
            }
        }
        val rf = c.open(remotePath, EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC))
        try {
            val buf = ByteArray(CHUNK)
            var offset = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    rf.write(offset, buf, 0, n)
                    offset += n
                    onProgress(offset, contentLength)
                }
            }
        } finally {
            rf.close()
        }
    }

    override suspend fun mkdir(path: String) {
        withContext(Dispatchers.IO) { ensure().mkdir(path) }
    }

    override suspend fun rename(path: String, newName: String) {
        withContext(Dispatchers.IO) { ensure().rename(path, joinPath(parentPath(path), newName)) }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            val c = ensure()
            val fileName = path.trimEnd('/').substringAfterLast('/')
            val parent = parentPath(path)
            val siblings = c.ls(if (parent == "/" || parent.isBlank()) "/" else parent)
            val isDir = siblings.any { it.name == fileName && it.isDirectory }
            if (isDir) c.rmdir(path) else c.rm(path)
        }
    }

    /** проверка существования name в каталоге dir (простая защита от сопоставления). */
    suspend fun exists(dir: String, name: String): Boolean {
        return useSession { c ->
            c.ls(if (dir.isBlank() || dir == "/") "/" else dir)
                .any { it.name == name }
        }
    }

    override fun close() {
        val s = sftp
        val h = ssh
        sftp = null
        ssh = null
        if (s == null && h == null) return
        // close() вызывается из onDispose в UI-потоке: sshj при закрытии шлёт пакеты по сети,
        // что приводит к NetworkOnMainThreadException. Закрываем в фоне, ошибки игнорируем.
        closerScope.launch {
            runCatching { s?.close() }
            runCatching { h?.disconnect() }
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 22
        private const val CHUNK: Int = 64 * 1024
        private val closerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
