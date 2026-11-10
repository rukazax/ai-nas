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
import ru.ainas.app.settings.SettingsStore
import ru.ainas.app.sync.FolderSync
import ru.ainas.app.up.UpdateChecker

/**
 * Application-класс: поднимает WorkManager-планирование (автозагрузка фото +
 * пары папок), следит за MediaStore (Instant upload) и проверяет обновления.
 */
class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        registerFullBouncyCastle()
        appScope.launch {
            val settings = SettingsStore.current(this@App)
            PhotoSync.schedulePeriodic(this@App, settings.photoWifiOnly)
            FolderSync.schedulePeriodic(this@App, settings.photoWifiOnly)
        }
        registerInstantUploadObserver()
        appScope.launch {
            UpdateChecker.backgroundCheck(this@App)
        }
    }

    /**
     * В Android встроен урезанный BC без X25519 — sshj падает на key exchange.
     * Подменяем его полным BouncyCastle из пакета.
     */
    private fun registerFullBouncyCastle() {
        try {
            val current = java.security.Security.getProvider("BC")
            if (current == null || !current.javaClass.name.startsWith("org.bouncycastle")) {
                java.security.Security.removeProvider("BC")
                java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            }
        } catch (_: Exception) {
        }
    }

    private fun registerInstantUploadObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Новое фото/видео: мгновенная выгрузка фото и сверка пар папок
                PhotoSync.kickOnce(this@App)
                FolderSync.kickOnce(this@App)
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
    }
}
