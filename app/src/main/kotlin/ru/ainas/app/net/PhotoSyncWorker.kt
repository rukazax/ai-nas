package ru.ainas.app.net

import android.content.ContentUris
import ru.ainas.app.auth.AuthStore
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
import ru.ainas.app.settings.SettingsStore
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Отдельный DataStore — метка последней выгрузки не трогает auth-ключи. */
internal val Context.photoPrefsStore by preferencesDataStore(name = "ainas_photo")

/** Периодическое планирование + мгновенный пинок от ContentObserver (Instant upload). */
object PhotoSync {

    const val UNIQUE_PERIODIC = "ainas-photo-periodic"
    const val UNIQUE_ONESHOT = "ainas-photo-once"

    private val KEY_LAST_SYNC = longPreferencesKey("photo/last_sync")

    /** Периодическая выгрузка не реже, чем раз в 15 минут (минимум WorkManager). */
    fun schedulePeriodic(context: Context, wifiOnly: Boolean = true) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<PhotoSyncWorker>(15L, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Моментальная очередь после ContentObserver.onChange. */
    fun kickOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<PhotoSyncWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_ONESHOT, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun lastSync(context: Context): Long =
        context.photoPrefsStore.data.first()[KEY_LAST_SYNC] ?: 0L

    suspend fun markSynced(context: Context, secondsUtc: Long) {
        context.photoPrefsStore.edit { prefs ->
            val prev = prefs[KEY_LAST_SYNC] ?: 0L
            if (secondsUtc > prev) prefs[KEY_LAST_SYNC] = secondsUtc
        }
    }
}

/**
 * Авто-выгрузка изображений: MediaStore -> SFTP /srv/nas/data/photo/<device-name>/YYYY/MM/.
 * Дедупликация: метка последнего успешного времени + проверка файла на сервере.
 */
class PhotoSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = SettingsStore.current(applicationContext)
        if (!settings.photoSyncEnabled) return@withContext Result.success()
        val auth = AuthStore.current(applicationContext) ?: return@withContext Result.success()
        val resolver = applicationContext.contentResolver
        val last = PhotoSync.lastSync(applicationContext)
        var newest = last

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        val selection = MediaStore.Images.Media.DATE_MODIFIED + " > ?"
        val args = arrayOf(last.toString())

        val candidates = ArrayList<Pair<Uri, Long>>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            MediaStore.Images.Media.DATE_MODIFIED + " ASC",
        ).use { cursor ->
            if (cursor != null) {
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val tsCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val ts = cursor.getLong(tsCol)
                    if (ts <= last) continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idCol),
                    )
                    candidates.add(uri to ts)
                    if (ts > newest) newest = ts
                }
            }
        }

        if (candidates.isEmpty()) {
            return@withContext Result.success()
        }

        val sftp = SftpProvider(
            auth.host, SftpProvider.DEFAULT_PORT, auth.user, auth.pass,
            altHost = auth.homeHost.takeIf { !ru.ainas.app.auth.NasEndpoints.isLocalHost(auth.host) },
        )
        try {
            val device = deviceName(applicationContext)
            for ((uri, ts) in candidates) {
                val calendar = Calendar.getInstance(Locale.US).apply { timeInMillis = ts * 1000L }
                val year = String.format(Locale.US, "%04d", calendar[Calendar.YEAR])
                val month = String.format(Locale.US, "%02d", calendar[Calendar.MONTH] + 1)
                val remoteDir = "/srv/nas/data/photo/" + device + "/" + year + "/" + month + "/"
                val fileName = displayName(applicationContext, uri) ?: fallbackName(ts)
                val remotePath = remoteDir + fileName

                if (!sftp.exists(remoteDir, fileName)) {
                    val uploaded = resolver.openInputStream(uri)?.use { input ->
                        sftp.putStream(input, -1L, remotePath)
                    } ?: false
                    if (!uploaded) {
                        return@withContext Result.retry()
                    }
                }
                PhotoSync.markSynced(applicationContext, ts)
            }
            Result.success()
        } finally {
            sftp.close()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                name = cursor.getString(col)
            }
        }
        return name
    }

    private fun fallbackName(secondsUtc: Long): String = "IMG_" + secondsUtc + ".jpg"

    private fun deviceName(context: Context): String {
        val custom = Settings.Global.getString(context.contentResolver, "device_name")
        val raw = custom ?: Build.MODEL
        return raw.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
