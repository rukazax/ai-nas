package ru.ainas.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.ainas.app.R
import ru.ainas.app.auth.AuthStore
import ru.ainas.app.net.PhotoSync
import ru.ainas.app.settings.AppSettings
import ru.ainas.app.settings.FolderPair
import ru.ainas.app.settings.SettingsStore
import ru.ainas.app.settings.ThemeMode
import ru.ainas.app.sync.FolderSync
import ru.ainas.app.up.UpdateChecker
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Готовые папки телефона для быстрого добавления пары. */
private val PRESET_LOCAL_FOLDERS = listOf(
    "/storage/emulated/0/DCIM/Camera" to "Камера",
    "/storage/emulated/0/Pictures" to "Изображения",
    "/storage/emulated/0/Download" to "Загрузки",
    "/storage/emulated/0/Documents" to "Документы",
    "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media" to "WhatsApp",
)

/**
 * Страница «Настройки»: аккаунт, сервер, автозагрузка фото, пары папок
 * (Syncthing-подобная синхронизация), тема, обновления, выход.
 */
@Composable
fun SettingsScreen(
    auth: ru.ainas.app.auth.AuthState,
    settings: AppSettings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var photoLastSync by remember { mutableStateOf(0L) }
    var folderLastRun by remember { mutableStateOf(0L) }
    var folderLastResult by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        photoLastSync = PhotoSync.lastSync(context) * 1000L
        folderLastRun = FolderSync.lastRun(context)
        folderLastResult = FolderSync.lastResult(context)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(44.dp))
        Text(
            text = stringResource(R.string.settings_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        SectionTitle(stringResource(R.string.settings_account))
        Text(
            text = auth.userLine(),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        TextButton(
            onClick = { scope.launch { AuthStore.clearAuth(context) } },
            modifier = Modifier.padding(start = 12.dp),
        ) { Text(stringResource(R.string.drawer_logout), color = MaterialTheme.colorScheme.error) }

        SectionDivider()

        // ---- Сервер ----
        SectionTitle(stringResource(R.string.settings_server))
        var publicHost by remember(settings.publicHost) { mutableStateOf(settings.publicHost) }
        OutlinedTextField(
            value = publicHost,
            onValueChange = { publicHost = it },
            label = { Text(stringResource(R.string.settings_public_host)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )
        var homeHost by remember(settings.publicHost) { mutableStateOf(auth.homeHost) }
        OutlinedTextField(
            value = homeHost,
            onValueChange = { homeHost = it },
            label = { Text(stringResource(R.string.settings_home_host)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )
        TextButton(
            onClick = {
                scope.launch {
                    SettingsStore.setPublicHost(context, publicHost)
                    ru.ainas.app.auth.AuthStore.setHomeHost(context, homeHost)
                }
            },
            modifier = Modifier.padding(start = 12.dp),
        ) { Text(stringResource(R.string.settings_save)) }

        SectionDivider()

        // ---- Автозагрузка фото ----
        SectionTitle(stringResource(R.string.settings_photo_sync))
        SwitchRow(
            title = stringResource(R.string.settings_photo_enabled),
            checked = settings.photoSyncEnabled,
            onChange = { on ->
                scope.launch {
                    SettingsStore.setPhotoSyncEnabled(context, on)
                    if (on) PhotoSync.schedulePeriodic(context, settings.photoWifiOnly)
                }
            },
        )
        SwitchRow(
            title = stringResource(R.string.settings_photo_wifi),
            checked = settings.photoWifiOnly,
            onChange = { on ->
                scope.launch {
                    SettingsStore.setPhotoWifiOnly(context, on)
                    if (settings.photoSyncEnabled) PhotoSync.schedulePeriodic(context, on)
                }
            },
        )
        Text(
            text = stringResource(R.string.settings_photo_last) + " " + formatStamp(photoLastSync),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        TextButton(
            onClick = {
                PhotoSync.kickOnce(context)
                scope.launch {
                    kotlinx.coroutines.delay(2500)
                    photoLastSync = PhotoSync.lastSync(context) * 1000L
                }
            },
            modifier = Modifier.padding(start = 12.dp),
        ) { Text(stringResource(R.string.settings_sync_now)) }

        SectionDivider()

        // ---- Синхронизация папок ----
        SectionTitle(stringResource(R.string.settings_folder_sync))
        SwitchRow(
            title = stringResource(R.string.settings_folder_enabled),
            checked = settings.folderSyncEnabled,
            onChange = { on ->
                scope.launch {
                    SettingsStore.setFolderSyncEnabled(context, on)
                    if (on) FolderSync.schedulePeriodic(context, settings.photoWifiOnly)
                }
            },
        )
        Text(
            text = stringResource(R.string.settings_folder_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        settings.folderPairs.forEach { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pair.localPath.substringAfterLast('/'),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = pair.localPath + " → " + pair.remotePath +
                            if (pair.twoWay) " ⇄" else " ↑",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        SettingsStore.setFolderPairs(
                            context,
                            settings.folderPairs.filter { it.id != pair.id },
                        )
                    }
                }) { Text(stringResource(R.string.dialog_cancel), fontSize = 12.sp) }
            }
        }
        Text(
            text = stringResource(R.string.settings_folder_last) + " " + formatStamp(folderLastRun) +
                if (folderLastResult.isNotBlank()) " · " + folderLastResult else "",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row {
            var showAdd by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showAdd = true },
                modifier = Modifier.padding(start = 12.dp),
            ) { Text(stringResource(R.string.settings_folder_add)) }
            TextButton(onClick = {
                FolderSync.kickOnce(context)
                scope.launch {
                    kotlinx.coroutines.delay(2500)
                    folderLastRun = FolderSync.lastRun(context)
                    folderLastResult = FolderSync.lastResult(context)
                }
            }) { Text(stringResource(R.string.settings_sync_now)) }

            if (showAdd) {
                AddPairDialog(
                    onDismiss = { showAdd = false },
                    onAdd = { pair ->
                        showAdd = false
                        scope.launch {
                            SettingsStore.setFolderPairs(context, settings.folderPairs + pair)
                            FolderSync.kickOnce(context)
                        }
                    },
                )
            }
        }

        SectionDivider()

        // ---- Оформление ----
        SectionTitle(stringResource(R.string.settings_theme))
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                val selected = settings.themeMode == mode
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { scope.launch { SettingsStore.setThemeMode(context, mode) } },
                ) {
                    Text(
                        text = stringResource(
                            when (mode) {
                                ThemeMode.SYSTEM -> R.string.theme_system
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.DARK -> R.string.theme_dark
                            },
                        ),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        SectionDivider()

        // ---- Обновления ----
        SectionTitle(stringResource(R.string.settings_updates))
        val version = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
        }
        var checking by remember { mutableStateOf(false) }
        var checkedFlag by remember { mutableStateOf<Boolean?>(null) }
        var installEntry by remember { mutableStateOf<ru.ainas.app.up.UpdateEntry?>(null) }
        var downloading by remember { mutableStateOf(false) }
        Text(
            text = stringResource(R.string.settings_version) + " " + version,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        TextButton(
            enabled = !checking,
            onClick = {
                checking = true
                scope.launch {
                    val entry = UpdateChecker.check(version)
                    checkedFlag = entry != null
                    installEntry = entry
                    checking = false
                }
            },
            modifier = Modifier.padding(start = 12.dp),
        ) { Text(stringResource(R.string.drawer_check_updates)) }
        if (checking) {
            Text(
                text = stringResource(R.string.update_checking),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp),
            )
        } else if (checkedFlag == false) {
            Text(
                text = stringResource(R.string.update_none),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        installEntry?.let { entry ->
            AlertDialog(
                onDismissRequest = { installEntry = null },
                title = { Text(stringResource(R.string.update_available_title)) },
                text = { Text(stringResource(R.string.update_available_body, entry.tagName)) },
                confirmButton = {
                    TextButton(enabled = !downloading, onClick = {
                        downloading = true
                        scope.launch {
                            runCatching {
                                val apk = UpdateChecker.downloadApk(entry, context) { _, _ -> }
                                UpdateChecker.installApk(context, apk)
                            }
                            downloading = false
                            installEntry = null
                        }
                    }) { Text(stringResource(R.string.update_install)) }
                },
                dismissButton = {
                    TextButton(onClick = { installEntry = null }) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                },
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AddPairDialog(onDismiss: () -> Unit, onAdd: (FolderPair) -> Unit) {
    var local by remember { mutableStateOf(PRESET_LOCAL_FOLDERS.first().first) }
    var remote by remember { mutableStateOf("/srv/nas/data/sync/Camera") }
    var twoWay by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_folder_add_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_folder_pick_local), fontSize = 12.sp)
                PRESET_LOCAL_FOLDERS.forEach { (path, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = local == path,
                            onClick = {
                                local = path
                                remote = "/srv/nas/data/sync/" + path.substringAfterLast('/')
                            },
                        )
                        Text(label, fontSize = 13.sp)
                    }
                }
                OutlinedTextField(
                    value = remote,
                    onValueChange = { remote = it },
                    label = { Text(stringResource(R.string.settings_folder_remote)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = twoWay, onCheckedChange = { twoWay = it })
                    Text(
                        stringResource(R.string.settings_folder_two_way),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = remote.isNotBlank(), onClick = {
                onAdd(
                    FolderPair(
                        id = java.util.UUID.randomUUID().toString(),
                        localPath = local,
                        remotePath = remote.trim(),
                        twoWay = twoWay,
                    ),
                )
            }) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

private fun formatStamp(ms: Long): String =
    if (ms <= 0L) "—" else {
        val diff = System.currentTimeMillis() - ms
        when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "только что"
            diff < TimeUnit.HOURS.toMillis(1) ->
                (diff / TimeUnit.MINUTES.toMillis(1)).toString() + " мин назад"
            diff < TimeUnit.DAYS.toMillis(1) ->
                (diff / TimeUnit.HOURS.toMillis(1)).toString() + " ч назад"
            else -> java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(java.util.Date(ms))
        }
    }
