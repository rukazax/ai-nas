package ru.ainas.app.ui
import ru.ainas.app.favorites.FavoritesStore

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.ainas.app.R
import ru.ainas.app.auth.AuthState
import ru.ainas.app.net.LocalProvider
import ru.ainas.app.net.Protocol
import ru.ainas.app.net.SftpProvider
import ru.ainas.app.net.SmbProvider
import ru.ainas.app.net.Storage
import ru.ainas.app.net.StorageListEntry
import ru.ainas.app.net.WebDavProvider
import ru.ainas.app.net.formatSize
import ru.ainas.app.net.joinPath
import ru.ainas.app.net.parentPath
import ru.ainas.app.settings.AppSettings
import ru.ainas.app.settings.SettingsStore
import java.io.File

/** Источники файл-менеджера: 0 SFTP, 1 SMB, 2 WebDAV, 3 телефон. */
private const val SOURCE_PHONE = 3

/** Стартовая папка SFTP: пул данных NAS. */
private const val SFTP_START_PATH = "/srv/nas/data"

/**
 * Страница «Файлы»: четыре источника (SFTP/SMB/WebDAV/телефон), вкладки
 * Файлы/Загрузки/Избранное, переключатель фото/видео галереи.
 */
@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
fun FilesScreen(
    auth: AuthState,
    settings: AppSettings,
    onBackToDashboard: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    var currentPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<StorageListEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    var selected by remember { mutableStateOf<StorageListEntry?>(null) }
    var transferProgress by remember { mutableStateOf<String?>(null) }
    var downloadsVersion by remember { mutableStateOf(0) }
    var renameEntry by remember { mutableStateOf<StorageListEntry?>(null) }
    var newFolderPrompt by remember { mutableStateOf(false) }
    val isPhone = sourceIndex == SOURCE_PHONE
    val galleryOn = settings.galleryEnabled

    val progressTemplate = stringResource(R.string.progress_downloading)
    val loadErrorText = stringResource(R.string.files_load_error)

    val downloadsDir = remember { File(context.filesDir, "Downloads").also { it.mkdirs() } }

    val storage: Storage = remember(sourceIndex, auth.host, auth.user, auth.pass, settings.smbShare) {
        when (sourceIndex) {
            0 -> SftpProvider(
                auth.host, SftpProvider.DEFAULT_PORT, auth.user, auth.pass,
                altHost = auth.homeHost.takeIf { !ru.ainas.app.auth.NasEndpoints.isLocalHost(auth.host) },
            )
            1 -> SmbProvider(auth.smbHost(), settings.smbShare, auth.user, auth.pass)
            2 -> WebDavProvider(
                baseUrl = "https://" + auth.host + "/remote.php/dav/files/" + auth.user + "/",
                user = auth.user,
                password = auth.pass,
            )
            else -> LocalProvider()
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
        if (!galleryOn) reload()
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
        val key = FavoritesStore.keyOf(Protocol.entries[sourceIndex.coerceAtMost(2)], entry.name, entry.path)
        return key in favorites
    }

    fun toggleFavorite(entry: StorageListEntry) {
        scope.launch {
            FavoritesStore.toggle(context, FavoritesStore.keyOf(Protocol.entries[sourceIndex.coerceAtMost(2)], entry.name, entry.path))
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

    /** Файл с телефона -> NAS: /srv/nas/data/inbox/<имя> по SFTP. */
    fun uploadToNas(entry: StorageListEntry) {
        if (entry.isDir) return
        val target = entry
        startTransfer {
            val sftp = SftpProvider(
                auth.host, SftpProvider.DEFAULT_PORT, auth.user, auth.pass,
                altHost = auth.homeHost.takeIf { !ru.ainas.app.auth.NasEndpoints.isLocalHost(auth.host) },
            )
            try {
                val inbox = "/srv/nas/data/inbox"
                runCatching { sftp.mkdir(inbox) }
                sftp.upload(File(target.path), joinPath(inbox, target.name)) { sent, total ->
                    transferProgress = formatTransfer(sent, total)
                }
            } finally {
                sftp.close()
            }
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

    BackHandler(enabled = !galleryOn) {
        if (currentPath.isNotBlank() && currentPath != "/") {
            currentPath = parentPath(currentPath)
        } else {
            onBackToDashboard()
        }
    }

    val expanded = false // страницы пейджера — телефонная ширина; foldable-деталь отключена
    val shownEntry = selected

    Column(modifier = Modifier.fillMaxSize()) {
        // шапка: edge-to-edge, отступ под статус-бар
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
                    Text(
                        text = stringResource(R.string.files_gallery_toggle),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                    Switch(
                        checked = galleryOn,
                        onCheckedChange = { on ->
                            scope.launch { SettingsStore.setGalleryEnabled(context, on) }
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        stringResource(R.string.protocol_sftp),
                        stringResource(R.string.protocol_smb),
                        stringResource(R.string.protocol_webdav),
                        stringResource(R.string.source_phone),
                    ).forEachIndexed { index, label ->
                        Surface(
                            shape = CircleShape,
                            color = if (index == sourceIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (index == sourceIndex) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            onClick = {
                                sourceIndex = index
                                currentPath = if (index == 0) SFTP_START_PATH else ""
                                selected = null
                                tab = 0
                            },
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                    Box(Modifier.weight(1f))
                    if (!galleryOn) {
                        TextButton(onClick = { pickUpload.launch("*/*") }) {
                            Text(stringResource(R.string.fab_upload), fontSize = 12.sp)
                        }
                    }
                }

                if (galleryOn) {
                    // режим галереи: табы файл-менеджера не нужны
                    HorizontalDivider()
                } else {
                    val tabs = if (isPhone) {
                        listOf(stringResource(R.string.tab_files), stringResource(R.string.tab_downloads))
                    } else {
                        listOf(
                            stringResource(R.string.tab_files),
                            stringResource(R.string.tab_downloads),
                            stringResource(R.string.tab_favorites),
                        )
                    }
                    TabRow(selectedTabIndex = tab.coerceAtMost(tabs.size - 1)) {
                        tabs.forEachIndexed { index, label ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (galleryOn) {
                GalleryScreen(
                    storage = storage,
                    rootPath = if (isPhone) "/DCIM" else settings.galleryRoot,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                when (tab.coerceAtMost(if (isPhone) 1 else 2)) {
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
                            sourceIndex = when (parsed.first) {
                                Protocol.SMB -> 1
                                Protocol.WEBDAV -> 2
                                else -> 0
                            }
                            currentPath = parentPath(parsed.third)
                            tab = 0
                            selected = null
                        },
                        onRemove = { key -> scope.launch { FavoritesStore.remove(context, key) } },
                    )
                }
            }
        }
    }

    if (shownEntry != null) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { selected = null }) {
            DetailActions(
                entry = shownEntry,
                favorite = !isPhone && isFavorite(shownEntry),
                progress = transferProgress,
                onOpen = { openEntry(shownEntry) },
                onSave = { saveEntry(shownEntry) },
                onSend = { sendEntry(shownEntry) },
                onRename = if (isPhone) null else {
                    { renameEntry = shownEntry; selected = null }
                },
                onDelete = { deleteEntry(shownEntry) },
                onFavoriteToggle = if (isPhone) null else {
                    {
                        toggleFavorite(shownEntry)
                        selected = null
                    }
                },
                onUploadToNas = if (isPhone) {
                    { uploadToNas(shownEntry) }
                } else {
                    null
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
