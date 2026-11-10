package ru.ainas.app.ui
import ru.ainas.app.favorites.FavoritesStore

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.ainas.app.R
import ru.ainas.app.auth.AuthState
import ru.ainas.app.net.Protocol
import ru.ainas.app.net.SftpProvider
import ru.ainas.app.net.SmbProvider
import ru.ainas.app.net.Storage
import ru.ainas.app.net.StorageListEntry
import ru.ainas.app.net.WebDavProvider
import ru.ainas.app.net.formatSize
import ru.ainas.app.net.joinPath
import ru.ainas.app.net.parentPath
import java.io.File

/**
 * Экран файл-менеджера: отдельная навигация (не в пейджере), три протокола,
 * вкладки Файлы/Загрузки/Избранное, свайп-вниз-закрыть, foldable list+detail.
 */
@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
fun FilesScreen(
    widthSize: WindowWidthSizeClass,
    auth: AuthState,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var protocol by remember { mutableStateOf(Protocol.SFTP) }
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<StorageListEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<StorageListEntry?>(null) }
    var transferProgress by remember { mutableStateOf<String?>(null) }
    var downloadsVersion by remember { mutableStateOf(0) }
    var renameEntry by remember { mutableStateOf<StorageListEntry?>(null) }
    var newFolderPrompt by remember { mutableStateOf(false) }
    val pullDown = remember { mutableStateOf(0f) }

    val progressTemplate = stringResource(R.string.progress_downloading)
    val loadErrorText = stringResource(R.string.files_load_error)

    val downloadsDir = remember { File(context.filesDir, "Downloads").also { it.mkdirs() } }

    val storage: Storage = remember(protocol, auth.host, auth.user, auth.pass) {
        when (protocol) {
            Protocol.SFTP -> SftpProvider(auth.host, SftpProvider.DEFAULT_PORT, auth.user, auth.pass)
            Protocol.SMB -> SmbProvider(auth.host, "nas-data", auth.user, auth.pass)
            Protocol.WEBDAV -> WebDavProvider(
                baseUrl = "https://" + auth.host + "/remote.php/dav/files/" + auth.user + "/",
                user = auth.user,
                password = auth.pass,
            )
        }
    }
    DisposableEffect(storage) {
        onDispose { storage.close() }
    }

    fun formatTransfer(sent: Long, total: Long): String {
        val totalText = if (total > 0) formatSize(total) else "…"
        return String.format(progressTemplate, formatSize(sent), totalText)
    }

    fun reload() {
        scope.launch {
            loading = true
            errorMessage = null
            try {
                entries = storage.list(currentPath)
            } catch (e: Exception) {
                entries = emptyList()
                errorMessage = e.message
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(currentPath, storage) {
        reload()
    }

    fun startTransfer(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                downloadsVersion = downloadsVersion + 1
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                transferProgress = null
            }
        }
    }

    fun deleteEntry(entry: StorageListEntry) {
        scope.launch {
            try {
                storage.delete(entry.path)
                reload()
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
    }

    val favorites by FavoritesStore.flow(context).collectAsStateWithLifecycle(initialValue = emptySet())

    fun isFavorite(entry: StorageListEntry): Boolean {
        val key = FavoritesStore.keyOf(protocol, entry.name, entry.path)
        return key in favorites
    }

    fun toggleFavorite(entry: StorageListEntry) {
        scope.launch {
            FavoritesStore.toggle(context, FavoritesStore.keyOf(protocol, entry.name, entry.path))
        }
    }

    fun openEntry(entry: StorageListEntry) {
        if (entry.isDir) {
            selected = null
            currentPath = entry.path
            return
        }
        val target = entry
        startTransfer {
            transferProgress = formatTransfer(0L, target.size)
            val local = File(context.cacheDir, "open" + File.separator + target.name)
            storage.download(target.path, local) { sent, total ->
                transferProgress = formatTransfer(sent, total)
            }
            openWithFileViewer(context, local, mimeFor(target.name))
        }
        selected = null
    }

    fun saveEntry(entry: StorageListEntry) {
        val target = entry
        startTransfer {
            val destination = File(downloadsDir, target.name)
            storage.download(target.path, destination) { sent, total ->
                transferProgress = formatTransfer(sent, total)
            }
        }
        selected = null
    }

    fun sendEntry(entry: StorageListEntry) {
        if (entry.isDir) return
        val target = entry
        startTransfer {
            val local = File(context.cacheDir, "share" + File.separator + target.name)
            storage.download(target.path, local) { sent, total ->
                transferProgress = formatTransfer(sent, total)
            }
            val shareTarget = androidx.core.content.FileProvider
                .getUriForFile(context, FILE_PROVIDER_AUTHORITY, local)
            val intent = Intent(Intent.ACTION_SEND)
                .setType(mimeFor(target.name))
                .putExtra(Intent.EXTRA_STREAM, shareTarget)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(Intent.createChooser(intent, null))
        }
        selected = null
    }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        val uri: Uri = picked
        startTransfer {
            val name = resolveDisplayName(context, uri)
            val staging = File(context.cacheDir, "upload" + File.separator + name)
            staging.parentFile?.mkdirs()
            val piped = context.contentResolver.openInputStream(uri)
            if (piped == null) {
                errorMessage = "empty stream"
                return@startTransfer
            }
            piped.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            storage.upload(staging, joinPath(currentPath, name)) { sent, total ->
                transferProgress = formatTransfer(sent, total)
            }
            reload()
        }
    }

    BackHandler {
        if (currentPath.isNotBlank() && currentPath != "/") {
            currentPath = parentPath(currentPath)
        } else {
            onClose()
        }
    }

    val expanded = widthSize == WindowWidthSizeClass.Expanded
    val shownEntry = selected

    Column(modifier = Modifier.fillMaxSize()) {
        // шапка: edge-to-edge, отступ под статус-бар (insets не съедаем)
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Spacer(Modifier.height(34.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.files_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box(Modifier.weight(1f))
                    if (transferProgress != null) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Protocol.entries.forEach { candidate ->
                        Surface(
                            shape = CircleShape,
                            color = if (candidate == protocol) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (candidate == protocol) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            onClick = {
                                protocol = candidate
                                currentPath = ""
                                selected = null
                            },
                        ) {
                            Text(
                                text = stringResource(candidate.titleRes),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = { pickUpload.launch("*/*") }) {
                        Text(stringResource(R.string.fab_upload), fontSize = 12.sp)
                    }
                    TextButton(onClick = { newFolderPrompt = true }) {
                        Text(stringResource(R.string.fab_new_folder), fontSize = 12.sp)
                    }
                }
                TabRow(selectedTabIndex = tab) {
                    listOf(
                        stringResource(R.string.tab_files),
                        stringResource(R.string.tab_downloads),
                        stringResource(R.string.tab_favorites),
                    ).forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
            }
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                when (tab) {
                    0 -> FileListPane(
                        entries = entries,
                        loading = loading,
                        errorMessage = errorMessage,
                        errorText = loadErrorText,
                        emptyText = stringResource(R.string.files_empty),
                        onEntryClick = { entry ->
                            if (entry.isDir) currentPath = entry.path else selected = entry
                        },
                        onEntryLongClick = { entry -> selected = entry },
                        onEntrySwipeDelete = { entry -> deleteEntry(entry) },
                    )
                    1 -> DownloadsPane(
                        dir = downloadsDir,
                        version = downloadsVersion,
                        emptyText = stringResource(R.string.downloads_empty),
                        onOpen = { file -> openWithFileViewer(context, file, mimeFor(file.name)) },
                        onDelete = { file ->
                            file.delete()
                            downloadsVersion = downloadsVersion + 1
                        },
                    )
                    else -> FavoritesPane(
                        favorites = favorites,
                        emptyText = stringResource(R.string.favorites_empty),
                        onOpen = { parsed ->
                            protocol = parsed.first
                            currentPath = parentPath(parsed.third)
                            tab = 0
                            selected = null
                        },
                        onRemove = { key -> scope.launch { FavoritesStore.remove(context, key) } },
                    )
                }

                // невидимая полоса у верхней кромки: свайп-вниз -> закрыть файл-менеджер
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(16.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { pullDown.value = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    pullDown.value = pullDown.value + dragAmount
                                    if (pullDown.value > 120f) onClose()
                                },
                            )
                        },
                )
            }

            val detailHost = shownEntry
            if (expanded && detailHost != null) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    DetailActions(
                        entry = detailHost,
                        favorite = isFavorite(detailHost),
                        progress = transferProgress,
                        onOpen = { openEntry(detailHost) },
                        onSave = { saveEntry(detailHost) },
                        onSend = { sendEntry(detailHost) },
                        onRename = {
                            renameEntry = detailHost
                            selected = null
                        },
                        onDelete = { deleteEntry(detailHost) },
                        onFavoriteToggle = {
                            toggleFavorite(detailHost)
                            selected = null
                        },
                        onDismiss = { selected = null },
                    )
                }
            }
        }
    }

    if (!expanded && shownEntry != null) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { selected = null }) {
            DetailActions(
                entry = shownEntry,
                favorite = isFavorite(shownEntry),
                progress = transferProgress,
                onOpen = { openEntry(shownEntry) },
                onSave = { saveEntry(shownEntry) },
                onSend = { sendEntry(shownEntry) },
                onRename = {
                    renameEntry = shownEntry
                    selected = null
                },
                onDelete = {
                    deleteEntry(shownEntry)
                    selected = null
                },
                onFavoriteToggle = {
                    toggleFavorite(shownEntry)
                    selected = null
                },
                onDismiss = { selected = null },
            )
        }
    }

    if (renameEntry != null) {
        RenameDialog(
            initialName = renameEntry?.name ?: "",
            onDismiss = { renameEntry = null },
            onConfirm = { newName ->
                val target = renameEntry
                renameEntry = null
                if (target != null && newName.isNotBlank() && newName.trim() != target.name) {
                    scope.launch {
                        try {
                            storage.rename(target.path, newName.trim())
                            reload()
                        } catch (e: Exception) {
                            errorMessage = e.message
                        }
                    }
                }
            },
        )
    }

    if (newFolderPrompt) {
        NewFolderDialog(
            onDismiss = { newFolderPrompt = false },
            onConfirm = { folderName ->
                newFolderPrompt = false
                if (folderName.isNotBlank()) {
                    scope.launch {
                        try {
                            storage.mkdir(joinPath(currentPath, folderName.trim()))
                            reload()
                        } catch (e: Exception) {
                            errorMessage = e.message
                        }
                    }
                }
            },
        )
    }
}
