package ru.ainas.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.ainas.app.R
import ru.ainas.app.net.Storage
import ru.ainas.app.net.StorageListEntry
import java.io.File
import java.security.MessageDigest

/** Медиа-расширения галереи. */
private val MEDIA_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "mp4", "mov", "webm", "mkv", "avi")

private fun isMedia(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in MEDIA_EXT
}

/** Кэш миниатюр и оригиналов в cacheDir (система чистит сама). */
private object GalleryCache {

    fun dir(context: android.content.Context, kind: String): File =
        File(context.cacheDir, kind).also { it.mkdirs() }

    fun fileFor(context: android.content.Context, kind: String, path: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(path.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(dir(context, kind), hex)
    }
}

/** Рекурсивный обход хранилища с ограничением глубины. */
private suspend fun walkMedia(
    storage: Storage,
    path: String,
    out: MutableList<StorageListEntry>,
    depth: Int,
) {
    if (depth > 6 || out.size > 2000) return
    val entries = try {
        storage.list(path)
    } catch (_: Exception) {
        return
    }
    for (entry in entries) {
        if (entry.name == "..") continue
        if (entry.isDir) {
            walkMedia(storage, entry.path, out, depth + 1)
        } else if (isMedia(entry.name)) {
            out.add(entry)
        }
    }
}

/**
 * Галерея фото/видео: сетка миниатюр + полноэкранный просмотр со свайпами.
 * [storage] — NAS (SFTP/SMB) или телефон (LocalProvider), [rootPath] — корень скана.
 */
@Composable
fun GalleryScreen(
    storage: Storage,
    rootPath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<StorageListEntry>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var viewerIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(storage, rootPath) {
        scanning = true
        val found = mutableListOf<StorageListEntry>()
        withContext(Dispatchers.IO) {
            walkMedia(storage, rootPath.ifBlank { "/" }, found, 0)
        }
        items = found.sortedByDescending { it.lastModified }
        scanning = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (scanning && items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.gallery_scanning),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        } else if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.gallery_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(items, key = { _, e -> e.path }) { index, entry ->
                    GalleryThumb(
                        storage = storage,
                        entry = entry,
                        onClick = { viewerIndex = index },
                    )
                }
            }
        }
    }

    if (viewerIndex >= 0 && viewerIndex < items.size) {
        GalleryViewer(
            storage = storage,
            items = items,
            startIndex = viewerIndex,
            onDismiss = { viewerIndex = -1 },
        )
    }
}

/** Миниатюра в сетке: скачиваем файл и уменьшаем до ~512px, кэшируем по пути. */
@Composable
private fun GalleryThumb(
    storage: Storage,
    entry: StorageListEntry,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(entry.path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(entry.path) { mutableStateOf(false) }

    LaunchedEffect(entry.path) {
        withContext(Dispatchers.IO) {
            val cache = GalleryCache.fileFor(context, "gallery_thumbs", entry.path)
            try {
                if (!cache.exists() || cache.length() == 0L) {
                    val tmp = File(context.cacheDir, "gallery_tmp.bin")
                    storage.download(entry.path, tmp) { _, _ -> }
                    val isVideo = entry.name.substringAfterLast('.', "").lowercase()
                        .let { it == "mp4" || it == "mov" || it == "webm" || it == "mkv" || it == "avi" }
                    if (isVideo) {
                        // Видео: кадр через MediaMetadataRetriever (BitmapFactory видео не декодирует)
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(tmp.absolutePath)
                            val frame = retriever.getFrameAtTime(1_000_000)
                            if (frame != null) {
                                java.io.FileOutputStream(cache).use { out ->
                                    frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                }
                                frame.recycle()
                            }
                        } finally {
                            retriever.release()
                        }
                    } else {
                        tmp.copyTo(cache, overwrite = true)
                    }
                    tmp.delete()
                }
                bitmap = decodeScaled(cache, 512)
                if (bitmap == null) failed = true
            } catch (_: Exception) {
                failed = true
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed) {
            Text("✕", color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
        } else {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (entry.name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "webm", "mkv", "avi")) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("▶", color = Color.White, fontSize = 13.sp) }
        }
    }
}

/** Полноэкранный просмотр: пейджер по файлам, видео — внешний плеер. */
@Composable
private fun GalleryViewer(
    storage: Storage,
    items: List<StorageListEntry>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { items.size })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val entry = items[page]
                ViewerItem(storage = storage, entry = entry)
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val idx = pagerState.currentPage
                Text(
                    text = items.getOrNull(idx)?.name ?: "",
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = (idx + 1).toString() + " / " + items.size,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                )
            }
        }
    }
    androidx.activity.compose.BackHandler { onDismiss() }
}

/** Один слайд: файл качается в кэш, картинка — сразу, видео — кнопкой наружу. */
@Composable
private fun ViewerItem(storage: Storage, entry: StorageListEntry) {
    val context = LocalContext.current
    var file by remember(entry.path) { mutableStateOf<File?>(null) }
    var error by remember(entry.path) { mutableStateOf(false) }
    var downloading by remember(entry.path) { mutableStateOf(false) }

    LaunchedEffect(entry.path) {
        val ext = entry.name.substringAfterLast('.', "bin").lowercase()
        val target = File(GalleryCache.fileFor(context, "gallery_full", entry.path).absolutePath + "." + ext)
        withContext(Dispatchers.IO) {
            try {
                val dst = target
                if (!dst.exists() || dst.length() == 0L) {
                    downloading = true
                    storage.download(entry.path, dst) { _, _ -> }
                }
                file = dst
            } catch (_: Exception) {
                error = true
            } finally {
                downloading = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val f = file
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        val isVideo = ext in setOf("mp4", "mov", "webm", "mkv", "avi")
        when {
            f != null && !isVideo -> AsyncImage(
                model = f,
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            f != null && isVideo -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎬 " + entry.name, color = Color.White, fontSize = 13.sp)
                androidx.compose.material3.TextButton(onClick = {
                    openWithFileViewer(context, f, mimeFor(entry.name))
                }) { Text("Открыть плеером", color = Color.White) }
            }
            error -> Text("Не удалось загрузить", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Декод bitmap с inSampleSize под [target] px по большей стороне. */
private fun decodeScaled(file: File, target: Int): Bitmap? {
    if (!file.exists() || file.length() == 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    var side = maxOf(bounds.outWidth, bounds.outHeight)
    while (side / 2 >= target) {
        sample *= 2
        side /= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 },
    )
}
