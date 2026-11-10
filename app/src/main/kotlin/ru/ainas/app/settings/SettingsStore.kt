package ru.ainas.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Отдельный DataStore настроек — не пересекается с auth/photo. */
internal val Context.settingsStore by preferencesDataStore(name = "ainas_settings")

/** Режим темы: следовать системе или форсировать светлую/тёмную. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Пара папок «как Syncthing»: локальная папка телефона <-> папка NAS.
 * [twoWay] = двусторонняя (новые файлы NAS тоже скачиваются на телефон).
 */
@Serializable
data class FolderPair(
    val id: String,
    val localPath: String,   // абсолютный путь на телефоне, напр. /storage/emulated/0/DCIM/Camera
    val remotePath: String,  // абсолютный путь на NAS, напр. /srv/nas/data/sync/Camera
    val twoWay: Boolean = false,
)

/** Снимок настроек приложения. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Публичный домен для noVNC/DSH, когда хост сессии локальный. Пусто = автоматически. */
    val publicHost: String = "",
    /** Имя SMB-шары. */
    val smbShare: String = "nas-data",
    /** Переключатель фото/видео галереи на вкладке файлового менеджера. */
    val galleryEnabled: Boolean = false,
    /** Корень галереи на NAS (рекурсивный обход). */
    val galleryRoot: String = "/srv/nas/data/photo",
    /** Автозагрузка фото включена. */
    val photoSyncEnabled: Boolean = true,
    /** Автозагрузка фото только по Wi-Fi. */
    val photoWifiOnly: Boolean = true,
    /** Синхронизация пар папок включена. */
    val folderSyncEnabled: Boolean = true,
    /** Пары папок телефон <-> NAS. */
    val folderPairs: List<FolderPair> = emptyList(),
)

/** Хранилище настроек: чтение потоком + точечные записи. */
object SettingsStore {

    private val KEY_THEME = stringPreferencesKey("settings/theme")
    private val KEY_PUBLIC_HOST = stringPreferencesKey("settings/public_host")
    private val KEY_SMB_SHARE = stringPreferencesKey("settings/smb_share")
    private val KEY_GALLERY_ENABLED = booleanPreferencesKey("settings/gallery_enabled")
    private val KEY_GALLERY_ROOT = stringPreferencesKey("settings/gallery_root")
    private val KEY_PHOTO_ENABLED = booleanPreferencesKey("settings/photo_enabled")
    private val KEY_PHOTO_WIFI = booleanPreferencesKey("settings/photo_wifi_only")
    private val KEY_FOLDERSYNC_ENABLED = booleanPreferencesKey("settings/foldersync_enabled")
    private val KEY_FOLDER_PAIRS = stringPreferencesKey("settings/folder_pairs")

    private val json = Json { ignoreUnknownKeys = true }

    fun flow(context: Context): Flow<AppSettings> =
        context.settingsStore.data.map { prefs -> parse(prefs) }

    suspend fun current(context: Context): AppSettings = flow(context).first()

    private fun parse(prefs: androidx.datastore.preferences.core.Preferences): AppSettings {
        val pairs = try {
            prefs[KEY_FOLDER_PAIRS]?.let { json.decodeFromString<List<FolderPair>>(it) }
        } catch (_: Exception) {
            null
        } ?: emptyList()
        return AppSettings(
            themeMode = prefs[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            publicHost = prefs[KEY_PUBLIC_HOST].orEmpty(),
            smbShare = prefs[KEY_SMB_SHARE]?.takeIf { it.isNotBlank() } ?: "nas-data",
            galleryEnabled = prefs[KEY_GALLERY_ENABLED] ?: false,
            galleryRoot = prefs[KEY_GALLERY_ROOT]?.takeIf { it.isNotBlank() } ?: "/srv/nas/data/photo",
            photoSyncEnabled = prefs[KEY_PHOTO_ENABLED] ?: true,
            photoWifiOnly = prefs[KEY_PHOTO_WIFI] ?: true,
            folderSyncEnabled = prefs[KEY_FOLDERSYNC_ENABLED] ?: true,
            folderPairs = pairs,
        )
    }

    suspend fun setThemeMode(context: Context, mode: ThemeMode) {
        context.settingsStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setPublicHost(context: Context, host: String) {
        context.settingsStore.edit { it[KEY_PUBLIC_HOST] = host.trim() }
    }

    suspend fun setSmbShare(context: Context, share: String) {
        context.settingsStore.edit { it[KEY_SMB_SHARE] = share.trim() }
    }

    suspend fun setGalleryEnabled(context: Context, enabled: Boolean) {
        context.settingsStore.edit { it[KEY_GALLERY_ENABLED] = enabled }
    }

    suspend fun setGalleryRoot(context: Context, root: String) {
        context.settingsStore.edit { it[KEY_GALLERY_ROOT] = root.trim() }
    }

    suspend fun setPhotoSyncEnabled(context: Context, enabled: Boolean) {
        context.settingsStore.edit { it[KEY_PHOTO_ENABLED] = enabled }
    }

    suspend fun setPhotoWifiOnly(context: Context, wifiOnly: Boolean) {
        context.settingsStore.edit { it[KEY_PHOTO_WIFI] = wifiOnly }
    }

    suspend fun setFolderSyncEnabled(context: Context, enabled: Boolean) {
        context.settingsStore.edit { it[KEY_FOLDERSYNC_ENABLED] = enabled }
    }

    suspend fun setFolderPairs(context: Context, pairs: List<FolderPair>) {
        context.settingsStore.edit { it[KEY_FOLDER_PAIRS] = json.encodeToString(pairs) }
    }
}
