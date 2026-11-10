package ru.ainas.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.launch
import ru.ainas.app.R
import ru.ainas.app.auth.AuthDeniedException
import ru.ainas.app.auth.AuthStore
import java.io.IOException

/**
 * Первый запуск: пустые поля Сервер/Пользователь/Пароль, пресеты подключения,
 * скрытый пароль и разворачиваемая справка. AuthStore.login() сам перебирает
 * маршруты сервера (интернет / домашняя сеть) и решает, token или Basic.
 */
@Composable
fun LandingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var showPass by rememberSaveable { mutableStateOf(false) }
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val unreachableError = stringResource(R.string.login_error_unreachable)
    val credentialsError = stringResource(R.string.login_error_credentials)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = stringResource(R.string.login_subtitle),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Быстрый выбор сервера — только подсказка хоста, поля остаются пустыми
        Text(
            text = stringResource(R.string.login_preset_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { host = "79e.ru" },
                label = { Text("🌍 79e.ru", fontSize = 12.sp) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            )
            AssistChip(
                onClick = { host = "192.168.88.35" },
                label = { Text("🏠 192.168.88.35", fontSize = 12.sp) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            )
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.login_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text(stringResource(R.string.login_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(
                        imageVector = if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (showPass) R.string.login_hide_password else R.string.login_show_password,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (errorText != null) {
            SelectionContainer {
                Text(
                    text = errorText!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        val loginLabel = stringResource(R.string.login_button)
        val progressLabel = stringResource(R.string.login_in_progress)
        OutlinedButton(
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            onClick = {
                if (busy || host.isBlank() || user.isBlank()) return@OutlinedButton
                busy = true
                errorText = null
                scope.launch {
                    val result = AuthStore.login(context, host, user, pass)
                    busy = false
                    result
                        .onSuccess { /* AppRoot сам увидит новый auth-поток */ }
                        .onFailure { failure ->
                            errorText = when (failure) {
                                is AuthDeniedException -> credentialsError
                                is IOException -> unreachableError
                                else -> failure.message ?: unreachableError
                            }
                        }
                }
            },
        ) {
            Text(text = if (busy) progressLabel else loginLabel, fontSize = 16.sp)
        }

        // Справка по подключению
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            TextButton(onClick = { helpOpen = !helpOpen }) {
                Icon(
                    Icons.Filled.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(if (helpOpen) R.string.help_hide else R.string.help_title),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = helpOpen) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.help_intro),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HelpLine(stringResource(R.string.help_item_public))
                    HelpLine(stringResource(R.string.help_item_home))
                    HelpLine(stringResource(R.string.help_item_creds))
                    HelpLine(stringResource(R.string.help_item_trouble))
                }
            }
        }
    }
}

@Composable
private fun HelpLine(text: String) {
    SelectionContainer {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
