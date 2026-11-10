package ru.ainas.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.ainas.app.R
import ru.ainas.app.auth.AuthState
import ru.ainas.app.auth.AuthStore
import ru.ainas.app.up.UpdateChecker
import ru.ainas.app.up.UpdateEntry

/**
 * Скрытый drawer: 4 вкладки, автозагрузка фото, обновления с GitHub, выход из аккаунта.
 */
@Composable
fun DrawerScreen(
    auth: AuthState,
    onOpenFiles: () -> Unit,
    onNavigatePage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var checkedFlag by remember { mutableStateOf<Boolean?>(null) }
    val pendingUpdate by UpdateChecker.pending.collectAsStateWithLifecycle()
    var installEntry by remember { mutableStateOf<UpdateEntry?>(null) }
    var downloading by remember { mutableStateOf(false) }

    val noUpdateText = stringResource(R.string.update_none)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, top = 24.dp)) {
            Text(text = stringResource(R.string.app_name), fontSize = 22.sp)
            Text(
                text = auth.userLine(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        DrawerItem(stringResource(R.string.drawer_dashboard)) {
            onNavigatePage(0)
        }
        DrawerItem(stringResource(R.string.drawer_desktop)) {
            onNavigatePage(1)
        }
        DrawerItem(stringResource(R.string.drawer_dsh)) {
            onNavigatePage(2)
        }
        DrawerItem(stringResource(R.string.drawer_files)) {
            onOpenFiles()
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.drawer_photo_sync),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp),
        )

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                enabled = !checking && !downloading,
                onClick = {
                    checking = true
                    scope.launch {
                        val version = runCatching {
                            context.packageManager
                                .getPackageInfo(context.packageName, 0)
                                .versionName
                        }.getOrNull() ?: "?"
                        val entry = UpdateChecker.check(version)
                        checkedFlag = entry != null
                        checking = false
                    }
                },
            ) {
                Text(stringResource(R.string.drawer_check_updates))
            }
        }

        if (checking) {
            Text(
                text = stringResource(R.string.update_checking),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp),
            )
        } else if (checkedFlag == false) {
            Text(
                text = noUpdateText,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp),
            )
        }

        HorizontalDivider()

        val logoutLabel = stringResource(R.string.drawer_logout)
        DrawerItem(logoutLabel) {
            scope.launch { AuthStore.clearAuth(context) }
        }
    }

    // Диалог «Обновить до X» и установка через PackageInstaller
    installEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { installEntry = null },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = { Text(stringResource(R.string.update_available_body, entry.tagName)) },
            confirmButton = {
                TextButton(enabled = !downloading, onClick = {
                    downloading = true
                    scope.launch {
                        try {
                            val apk = UpdateChecker.downloadApk(entry, context) { _, _ -> }
                            UpdateChecker.installApk(context, apk)
                        } catch (_: Exception) {
                            // молча: инсталлер сам покажет статус пользователю
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

    LaunchedEffect(pendingUpdate) {
        if (pendingUpdate != null && installEntry == null) {
            installEntry = pendingUpdate
        }
    }
}

@Composable
private fun DrawerItem(label: String, onClick: () -> Unit) {
    TextButton(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 8.dp),
        onClick = onClick,
    ) {
        Text(text = label, fontSize = 15.sp)
    }
}
