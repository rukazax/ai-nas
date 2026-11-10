package ru.ainas.app.favorites

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.ainas.app.net.Protocol

/**
 * «Избранное» файл-менеджера: ключ — это protocol|name|path в строке preferences,
 * чтобы Vault был постоянным, но не мешал снапшоту.
 */
object FavoritesStore {

    val KEY = stringSetPreferencesKey("favorites")

    private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

    /** Текущее множество ключей как Flow. */
    fun flow(context: Context): Flow<Set<String>> =
        context.favoritesDataStore.data.map { prefs -> prefs[KEY] ?: emptySet() }

    /** Стабильный ключ записи: protocol|имя|путь. */
    fun keyOf(protocol: Protocol, name: String, path: String): String =
        protocol.name + "|" + name + "|" + path

    /** Разбор ключа обратно в тройку (protocol, name, path). Null — ключ битый. */
    fun parse(key: String): Triple<Protocol, String, String>? {
        val protocol = key.substringBefore('|', "").let { raw ->
            Protocol.entries.firstOrNull { it.name == raw } ?: return null
        }
        val rest = key.substringAfter('|', "")
        val name = rest.substringBefore('|', "")
        val path = rest.substringAfter('|', "")
        if (name.isEmpty() || path.isEmpty()) return null
        return Triple(protocol, name, path)
    }

    /** Тоггл: есть — удалить, нет — добавить. */
    suspend fun toggle(context: Context, key: String) {
        context.favoritesDataStore.edit { prefs ->
            val current = prefs[KEY] ?: emptySet()
            prefs[KEY] = if (key in current) current - key else current + key
        }
    }

    /** Полное удаление записи из избранного. */
    suspend fun remove(context: Context, key: String) {
        context.favoritesDataStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet()) - key
        }
    }
}
