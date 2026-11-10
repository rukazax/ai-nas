package ru.ainas.app.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.ainas.app.auth.AuthStore
import ru.ainas.app.net.StorageListEntry
import ru.ainas.app.net.SftpProvider
import ru.ainas.app.settings.FolderPair
import ru.ainas.app.settings.SettingsStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Syncthing-подобная синхронизация папок: пары «папка телефона <-> папка NAS»
 * поверх SFTP. Периодический воркер + мгновенный пинок от ContentObserver.
 *
 * Алгоритм (v1, mtime+size):
 *  - выгрузка: локальный файл отсутствует на NAS, либо размеры различаются и локальный новее;
 *  - двусторонний режим: удалённый файл отсутствует локально, либо размеры различаются
 *    и удалённый новее — скачивается на телефон.
 */
internal val Context.folderSyncStore by preferencesDataStore(name = "ainas_foldersync")

object FolderSync {

    const val UNIQUE_PERIODIC = "ainas-foldersync-periodic"
    const val UNIQUE_ONESHOT = "ainas-foldersync-once"

    private val KEY_LAST_RUN = longPreferencesKey("foldersync/last_run")
    private val KEY_LAST_RESULT = stringPreferencesKey("foldersync/last_result")

    /** Периодическая сверка пар. */
    fun schedulePeriodic(context: Context, wifiOnly: Boolean = true) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<FolderSyncWorker>(15L, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Мгновенный запуск (после ContentObserver или кнопки «сейчас»). */
    fun kickOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<FolderSyncWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_ONESHOT, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun lastRun(context: Context): Long =
        context.folderSyncStore.data.first()[KEY_LAST_RUN] ?: 0L

    suspend fun lastResult(context: Context): String =
        context.folderSyncStore.data.first()[KEY_LAST_RESULT].orEmpty()

    internal suspend fun markRun(context: Context, result: String) {
        context.folderSyncStore.edit { prefs ->
            prefs[KEY_LAST_RUN] = System.currentTimeMillis()
            prefs[KEY_LAST_RESULT] = result
        }
    }

    fun formatTime(ms: Long): String =
        if (ms <= 0L) "—" else SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ms))
}

/** Максимальная глубина обхода и лимит передач за один прогон. */
private const val MAX_DEPTH = 12
private const val MAX_TRANSFERS = 300

class FolderSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = SettingsStore.current(applicationContext)
        if (!settings.folderSyncEnabled || settings.folderPairs.isEmpty()) {
            FolderSync.markRun(applicationContext, if (settings.folderPairs.isEmpty()) "нет пар" else "выключена")
            return@withContext Result.success()
        }
        val auth = AuthStore.current(applicationContext) ?: return@withContext Result.success()

        val sftp = SftpProvider(
            auth.host, SftpProvider.DEFAULT_PORT, auth.user, auth.pass,
            altHost = auth.homeHost.takeIf { !ru.ainas.app.auth.NasEndpoints.isLocalHost(auth.host) },
        )
        try {
            var up = 0
            var down = 0
            var failed = 0
            for (pair in settings.folderPairs) {
                val r = syncPair(sftp, pair)
                up += r.first
                down += r.second
                failed += r.third
            }
            val summary = buildString {
                append("↑").append(up).append(" ↓").append(down)
                if (failed > 0) append(" (ошибок: ").append(failed).append(")")
            }
            FolderSync.markRun(applicationContext, summary)
            Result.success()
        } catch (e: Exception) {
            FolderSync.markRun(applicationContext, "ошибка: " + (e.message ?: e.javaClass.simpleName))
            Result.success()
        } finally {
            runCatching { sftp.close() }
        }
    }

    /** Возвращает (выгружено, скачано, ошибок). */
    private suspend fun syncPair(sftp: SftpProvider, pair: FolderPair): Triple<Int, Int, Int> {
        var up = 0
        var down = 0
        var failed = 0

        val localRoot = File(pair.localPath)
        if (!localRoot.exists() || !localRoot.isDirectory) return Triple(0, 0, 0)

        val localFiles = HashMap<String, File>()
        walkLocal(localRoot, localFiles, 0)

        val remoteRoot = pair.remotePath.trimEnd('/')
        val remoteFiles = HashMap<String, StorageListEntry>()
        runCatching { sftp.mkdir(remoteRoot) }
        walkRemote(sftp, remoteRoot, remoteFiles, 0)

        if (remoteFiles.isEmpty() && runCatching { sftp.list(remoteRoot) }.isFailure) {
            // NAS недоступен по этому пути — ничего не делаем в этом прогоне
            return Triple(0, 0, 1)
        }

        // 1) Выгрузка новых/обновлённых локальных файлов
        for ((rel, file) in localFiles) {
            if (up + down + failed >= MAX_TRANSFERS) break
            val remote = remoteFiles[rel]
            val shouldUpload = remote == null ||
                (remote.size != file.length() && file.lastModified() / 1000 > remote.lastModified)
            if (!shouldUpload) continue
            try {
                val remotePath = remoteRoot + "/" + rel
                val parent = remotePath.substringBeforeLast('/', "")
                if (parent.isNotBlank()) runCatching { sftp.mkdir(parent) }
                sftp.upload(file, remotePath) { _, _ -> }
                up++
            } catch (_: Exception) {
                failed++
            }
        }

        // 2) Двусторонний режим: докачиваем новые/обновлённые файлы с NAS
        if (pair.twoWay) {
            for ((rel, entry) in remoteFiles) {
                if (up + down + failed >= MAX_TRANSFERS) break
                val local = File(localRoot, rel)
                val shouldDownload = !local.exists() ||
                    (local.length() != entry.size && entry.lastModified > local.lastModified() / 1000)
                if (!shouldDownload) continue
                try {
                    local.parentFile?.mkdirs()
                    sftp.download(entry.path, local) { _, _ -> }
                    down++
                } catch (_: Exception) {
                    failed++
                }
            }
        }
        return Triple(up, down, failed)
    }

    private fun walkLocal(dir: File, out: MutableMap<String, File>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            when {
                child.isDirectory -> walkLocal(child, out, depth + 1)
                child.isFile && child.length() > 0 -> out[child.name] = child // плоская папка
            }
        }
    }

    private suspend fun walkRemote(
        sftp: SftpProvider,
        path: String,
        out: MutableMap<String, StorageListEntry>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val entries = try {
            sftp.list(path)
        } catch (_: Exception) {
            return
        }
        for (entry in entries) {
            if (entry.name == "..") continue
            if (entry.isDir) {
                walkRemote(sftp, entry.path, out, depth + 1)
            } else if (entry.size > 0) {
                out[entry.name] = entry
            }
        }
    }
}
