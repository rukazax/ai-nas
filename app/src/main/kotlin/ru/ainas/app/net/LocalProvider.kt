package ru.ainas.app.net

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Локальное хранилище телефона как Storage: корень /storage/emulated/0.
 * Требует MANAGE_EXTERNAL_STORAGE (сид-загрузка — разрешение выдаётся в настройках системы).
 */
class LocalProvider(
    private val rootPath: String = Environment.getExternalStorageDirectory().absolutePath,
) : Storage {

    private fun resolve(path: String): File {
        val rel = path.trimStart('/')
        return if (rel.isBlank()) File(rootPath) else File(rootPath, rel)
    }

    override suspend fun list(path: String): List<StorageListEntry> = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        val parent = parentPath(if (path.isBlank()) "/" else path)
        val items = dir.listFiles()
            ?.filter { it.name != "." && it.name != ".." }
            ?.map {
                StorageListEntry(
                    name = it.name,
                    path = joinPath(if (path.isBlank()) "/" else path, it.name),
                    isDir = it.isDirectory,
                    size = if (it.isDirectory) 0L else it.length(),
                    lastModified = it.lastModified(),
                )
            }
            ?.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            .orEmpty()
        // За пределами корня добавляем ".."
        val abs = path.trimEnd('/')
        if (abs.isBlank() || abs == "/") items
        else listOf(StorageListEntry(name = "..", path = parent, isDir = true)) + items
    }

    override suspend fun download(remotePath: String, target: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val src = resolve(remotePath)
            if (!target.parentFile.exists()) target.parentFile.mkdirs()
            val total = src.length()
            val buf = ByteArray(DEFAULT_CHUNK)
            var sent = 0L
            src.inputStream().use { input ->
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
            val dst = resolve(remotePath)
            dst.parentFile?.mkdirs()
            val total = local.length()
            val buf = ByteArray(DEFAULT_CHUNK)
            var sent = 0L
            local.inputStream().use { input ->
                dst.outputStream().use { out ->
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

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        resolve(path).mkdirs()
        Unit
    }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        val src = resolve(path)
        val dst = File(src.parentFile, newName)
        src.renameTo(dst)
        Unit
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        resolve(path).deleteRecursively()
        Unit
    }

    override fun close() = Unit

    companion object {
        const val DEFAULT_CHUNK: Int = 64 * 1024

        /** Выдано ли разрешение «Все файлы» (API 30+). Для старых API считаем выданным. */
        fun hasAllFilesAccess(): Boolean =
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
    }
}
