package ru.ainas.app

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.ainas.app.net.PhotoSync
import ru.ainas.app.up.UpdateChecker

/**
 * Application-класс: поднимает WorkManager-планирование автозагрузки фото,
 * следит за MediaStore (Instant upload) и проверяет обновления на GitHub.
 */
class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        PhotoSync.schedulePeriodic(this)
        registerInstantUploadObserver()
        appScope.launch {
            UpdateChecker.backgroundCheck(this@App)
        }
    }

    private fun registerInstantUploadObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // ContentObserver выдёргивает воркер сразу после появления нового фото
                PhotoSync.kickOnce(this@App)
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
    }
}
