package ru.ainas.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.ainas.app.R
import ru.ainas.app.auth.AuthStore
import java.io.IOException

/**
 * Первый запуск: форма Сервер/Пользователь/Пароль + кнопка "Войти".
 * AuthStore.login() сам решит, NAS отдала pair-token или только Basic-auth.
 */
@Composable
fun LandingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("192.168.88.35") }
    var user by remember { mutableStateOf("ka") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val unreachableError = stringResource(R.string.login_error_unreachable)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                modifier = Modifier.fillMaxWidth(),
            )

            if (errorText != null) {
                Text(text = errorText!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
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
                                    is IOException -> unreachableError
                                    else -> failure.message ?: unreachableError
                                }
                            }
                    }
                },
            ) {
                Text(text = if (busy) progressLabel else loginLabel, fontSize = 16.sp)
            }

            TextButton(onClick = { host = "79e.ru"; user = "ka" }) {
                Text(text = "79e.ru", fontSize = 12.sp, color = Color(0xFF8AA2C0))
            }
        }
    }
}
