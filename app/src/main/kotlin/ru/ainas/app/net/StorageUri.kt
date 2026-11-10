package ru.ainas.app.net

import ru.ainas.app.R
import java.util.Locale

/** Доступные протоколы хранилищ. titleRes — имя протокола для UI. */
enum class Protocol(val titleRes: Int) {
    SFTP(R.string.protocol_sftp),
    SMB(R.string.protocol_smb),
    WEBDAV(R.string.protocol_webdav);
}

/** Дескриптор удалённого хранилища: всё, что нужно провайдеру для подключения. */
data class StorageUri(
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val share: String = "",          // только для SMB: имя шары, например "nas-data"
    val path: String = "/",          // текущий путь внутри хранилища
    val user: String,
    val password: String,
) {
    fun displayLabel(): String = when (protocol) {
        Protocol.SFTP -> "sftp://" + host + ":" + port + path
        Protocol.SMB -> "smb://" + host + "/" + share
        Protocol.WEBDAV -> "webdav://" + host + path
    }
}

/** Унифицированная запись о файле/каталоге любого протокола. */
interface StorageEntry {
    val name: String
    val path: String
    val isDir: Boolean
    val size: Long
    val lastModified: Long
}

data class StorageListEntry(
    override val name: String,
    override val path: String,
    override val isDir: Boolean,
    override val size: Long = 0L,
    override val lastModified: Long = 0L,
) : StorageEntry

/**
 * Общий контракт провайдера: список, обмен, служебные операции.
 * Реализации: SftpProvider (sshj), SmbProvider (jcifs-ng), WebDavProvider (sardine).
 */
interface Storage {
    suspend fun list(path: String): List<StorageListEntry>
    suspend fun download(remotePath: String, target: java.io.File, onProgress: (Long, Long) -> Unit)
    suspend fun upload(local: java.io.File, remotePath: String, onProgress: (Long, Long) -> Unit)
    suspend fun mkdir(path: String)
    suspend fun rename(path: String, newName: String)
    suspend fun delete(path: String)
    fun close()
}

/** Склеивание сегментов пути в единый формат "/a/b" (для корня использовать "/" или ""). */
fun joinPath(parent: String, name: String): String {
    val base = when {
        parent.isBlank() -> ""
        parent == "/" -> ""
        else -> parent.trimEnd('/')
    }
    return base + "/" + name
}

/** Родительский путь: "/a/b" -> "/a", "/x" -> "/", "/" -> "/". */
fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}

/** Человекочитаемый размер файла. */
fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 " + KB_LABEL
    val units = arrayOf(B_LABEL, KB_LABEL, MB_LABEL, GB_LABEL)
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    val rounded = (value * 10).toLong() / 10.0
    val text = if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
    return text + " " + units[unit]
}

private const val B_LABEL = "Б"
private const val KB_LABEL = "Кб"
private const val MB_LABEL = "Мб"
private const val GB_LABEL = "Гб"
