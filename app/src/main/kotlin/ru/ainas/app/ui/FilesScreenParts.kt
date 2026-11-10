package ru.ainas.app.ui
import ru.ainas.app.favorites.FavoritesStore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ru.ainas.app.R
import ru.ainas.app.net.Protocol
import ru.ainas.app.net.StorageListEntry
import ru.ainas.app.net.formatSize
import java.io.File
import kotlin.math.abs

internal const val FILE_PROVIDER_AUTHORITY = "ru.ai.nas.fileprovider"

/** Открыть локальный файл системным viewer'ом (через FileProvider). */
fun openWithFileViewer(context: Context, file: File, mime: String) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

/** mimetype по расширению имени файла. */
fun mimeFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "html", "htm" -> "text/html"
        "zip", "7z" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "mp4", "mkv", "avi" -> "video/*"
        "mp3", "flac", "ogg", "wav" -> "audio/*"
        else -> "application/octet-stream"
    }
}

/** Фото-строки не показываются на прямом SFTP — берём имя из ContentResolver (GetContent). */
fun resolveDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
                val value = cursor.getString(idx)
                if (!value.isNullOrBlank()) return value
            }
        }
    }
    return "upload_" + System.currentTimeMillis() + ".bin"
}

/** Список файлов текущего каталога с "..", свайп-влево = удалить, long-press = детали. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileListPane(
    entries: List<StorageListEntry>,
    loading: Boolean,
    errorMessage: String?,
    errorText: String,
    emptyText: String,
    onEntryClick: (StorageListEntry) -> Unit,
    onEntryLongClick: (StorageListEntry) -> Unit,
    onEntrySwipeDelete: (StorageListEntry) -> Unit,
) {
    if (loading && entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (errorMessage != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = errorText, color = MaterialTheme.colorScheme.error)
                Text(text = errorMessage, fontSize = 11.sp, color = Color.Gray)
            }
        }
        return
    }
    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 6.dp),
    ) {
        items(entries, key = { it.path + "/" + it.name }) { entry ->
            SwipeLeftToDeleteRow(onDelete = { onEntrySwipeDelete(entry) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onEntryClick(entry) },
                            onLongClick = { onEntryLongClick(entry) },
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            text = entry.name,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (entry.isDir) "…" else formatSize(entry.size),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Обёртка строки: горизонтальный жест не consumes вертикальный скролл; влево > 64px = delete. */
@Composable
internal fun SwipeLeftToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(onDelete) {
                awaitEachGesture {
                    val down = awaitDown()
                    val startY = down.position.y
                    var lastX = down.position.x
                    var traveledX = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        traveledX += change.position.x - lastX
                        lastX = change.position.x
                        val dy = abs(change.position.y - startY)
                        if (traveledX < -64f && dy < 48f) {
                            onDelete()
                            break
                        }
                        if (dy > 60f && abs(traveledX) < 24f) {
                            // вертикальный скролл — не считаем за свайп-удаление
                            break
                        }
                        if (change.changedToUp()) break
                    }
                }
            },
    ) {
        content()
    }
}

private suspend fun AwaitPointerEventScope.awaitDown() = awaitFirstDown(requireUnconsumed = false)

/** Вкладка «Загрузки»: файлы из Downloads в внутреннем хранилище приложения. */
@Composable
internal fun DownloadsPane(
    dir: File,
    version: Int,
    emptyText: String,
    onOpen: (File) -> Unit,
    onDelete: (File) -> Unit,
) {
    val files = remember(version) {
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    if (files.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.padding(top = 6.dp)) {
        items(files, key = { it.absolutePath }) { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickableCompat(onClick = { onOpen(file) }, onLongClick = { onDelete(file) })
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = file.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = formatSize(file.length()), fontSize = 11.sp)
                }
            }
        }
    }
}

/** Вкладка «Избранное»: тап открывает родителя файла в нужном протоколе. */
@Composable
internal fun FavoritesPane(
    favorites: Set<String>,
    emptyText: String,
    onOpen: (Triple<Protocol, String, String>) -> Unit,
    onRemove: (String) -> Unit,
) {
    val parsed = favorites
        .mapNotNull { key -> FavoritesStore.parse(key)?.let { key to it } }
        .sortedBy { it.second.third }
    if (parsed.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.padding(top = 6.dp)) {
        items(parsed, key = { it.first }) { pair ->
            val entry = pair.second
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickableCompat(
                        onClick = { onOpen(entry) },
                        onLongClick = { onRemove(pair.first) },
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = Color(0xFFF2A93B),
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(text = entry.second, fontSize = 15.sp)
                    Text(
                        text = entry.first.name + entry.third,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickableCompat(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** Действия над файлом: лист-деталь (foldable) и bottom sheet. */
@Composable
internal fun DetailActions(
    entry: StorageListEntry,
    favorite: Boolean,
    progress: String?,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSend: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(text = entry.name, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (entry.isDir) "…" else formatSize(entry.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (progress != null) {
            Text(text = progress, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onClick = onFavoriteToggle) {
            Icon(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = null)
            Text(if (favorite) stringResource(R.string.menu_remove_favorite) else stringResource(R.string.menu_add_favorite))
        }
        if (!entry.isDir) {
            TextButton(onClick = onOpen) {
                Text(stringResource(R.string.menu_open_file))
            }
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.menu_save_local))
            }
            TextButton(onClick = onSend) {
                Text(stringResource(R.string.menu_send))
            }
        }
        TextButton(onClick = onRename) {
            Text(stringResource(R.string.menu_rename))
        }
        TextButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.menu_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.dialog_cancel))
        }
    }
}

/** Диалог переименования. */
@Composable
internal fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.dialog_rename_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value) },
            ) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** Диалог новой папки. */
@Composable
internal fun NewFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_folder_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.dialog_rename_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value) },
            ) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
