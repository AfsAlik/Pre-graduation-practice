package ru.finhelper.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finhelper.app.R
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.AppTextField
import ru.finhelper.app.ui.components.LocalSnackbarHost
import ru.finhelper.app.ui.components.NoticeBanner
import ru.finhelper.app.ui.components.PrimaryButton
import ru.finhelper.app.ui.components.SyncStatusBar
import ru.finhelper.app.ui.components.LocalSyncStatus
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.toneColor

/** Текст ошибки формы, полученной от сервера. */
@Composable
private fun FormError(message: String?) {
    if (message != null) {
        NoticeBanner(message, tone = Tone.NEGATIVE, icon = Icons.Filled.AccountBalanceWallet)
    }
}

@Composable
fun LoginRoute(viewModel: AuthViewModel, onLoggedIn: () -> Unit, onRegister: () -> Unit, onForgot: () -> Unit) {
    val state by viewModel.login.collectAsStateWithLifecycle()
    LoginScreen(
        state = state,
        onEmailChange = { value -> viewModel.updateLogin { it.copy(email = value, emailError = null) } },
        onPasswordChange = { value -> viewModel.updateLogin { it.copy(password = value, passwordError = null) } },
        onCodeChange = { value -> viewModel.updateLogin { it.copy(totpCode = value.filter(Char::isDigit).take(8)) } },
        onServerUrlChange = { value -> viewModel.updateLogin { it.copy(serverUrl = value) } },
        onLogin = { viewModel.submitLogin(onLoggedIn) },
        onRegister = onRegister,
        onForgot = onForgot,
    )
}

/**
 * Экран «Вход». Содержит поле адреса сервера: на реальном телефоне в нем
 * указывается адрес компьютера с серверной частью в той же сети Wi-Fi.
 */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onCodeChange: (String) -> Unit = {},
    onServerUrlChange: (String) -> Unit = {},
    onLogin: () -> Unit = {},
    onRegister: () -> Unit = {},
    onForgot: () -> Unit = {},
) {
    var serverVisible by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHost.current) },
        topBar = { SyncStatusBar(LocalSyncStatus.current, onClick = {}) },
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Icon(
                Icons.Filled.AccountBalanceWallet, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            FormError(state.error)
            AppTextField(
                state.email, onEmailChange, stringResource(R.string.field_email),
                error = state.emailError, keyboardType = KeyboardType.Email,
            )
            AppTextField(
                state.password, onPasswordChange, stringResource(R.string.field_password),
                error = state.passwordError, password = true, imeAction = if (state.twoFactorRequired) ImeAction.Next else ImeAction.Done,
            )
            if (state.twoFactorRequired) {
                AppTextField(
                    state.totpCode, onCodeChange, stringResource(R.string.login_totp_code),
                    hint = stringResource(R.string.login_totp_hint), keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                )
            }
            if (serverVisible) {
                AppTextField(
                    state.serverUrl, onServerUrlChange, stringResource(R.string.login_server_url),
                    hint = stringResource(R.string.login_server_hint), keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                )
            }
            PrimaryButton(stringResource(R.string.login_submit), onLogin, loading = state.loading)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onRegister, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.login_register)) }
                TextButton(onClick = onForgot, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.login_forgot)) }
            }
            TextButton(onClick = { serverVisible = !serverVisible }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (serverVisible) R.string.login_hide_server else R.string.login_show_server))
            }
        }
    }
}

@Composable
fun RegisterRoute(viewModel: AuthViewModel, onBack: () -> Unit) {
    val state by viewModel.register.collectAsStateWithLifecycle()
    RegisterScreen(
        state = state,
        onBack = onBack,
        onChange = viewModel::updateRegister,
        onSubmit = { viewModel.submitRegister(onBack) },
    )
}

/** Экран «Регистрация» с обязательным согласием на обработку персональных данных. */
@Composable
fun RegisterScreen(
    state: RegisterUiState,
    onBack: () -> Unit = {},
    onChange: ((RegisterUiState) -> RegisterUiState) -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }
    AppScaffold(
        title = stringResource(R.string.register_title),
        onBack = onBack,
        bottomBar = {
            PrimaryButton(
                stringResource(R.string.register_submit), onSubmit, loading = state.loading,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            FormError(state.error)
            AppTextField(
                state.email, { value -> onChange { it.copy(email = value, emailError = null) } }, stringResource(R.string.field_email),
                error = state.emailError, keyboardType = KeyboardType.Email, modifier = Modifier.focusRequester(firstField),
            )
            AppTextField(state.displayName, { value -> onChange { it.copy(displayName = value) } }, stringResource(R.string.register_name))
            AppTextField(
                state.password, { value -> onChange { it.copy(password = value, passwordError = null) } },
                stringResource(R.string.field_password), error = state.passwordError,
                hint = stringResource(R.string.register_password_hint), password = true,
            )
            AppTextField(
                state.passwordRepeat, { value -> onChange { it.copy(passwordRepeat = value, repeatError = null) } },
                stringResource(R.string.register_password_repeat), error = state.repeatError, password = true, imeAction = ImeAction.Done,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(state.consent, role = Role.Checkbox) { value -> onChange { it.copy(consent = value, consentError = false) } },
            ) {
                Checkbox(checked = state.consent, onCheckedChange = null)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.register_consent), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.consentError) {
                Text(
                    stringResource(R.string.register_consent_error),
                    style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.NEGATIVE),
                )
            }
        }
    }
}

@Composable
fun RecoveryRoute(viewModel: AuthViewModel, onBack: () -> Unit) {
    val state by viewModel.recovery.collectAsStateWithLifecycle()
    RecoveryScreen(
        state = state,
        onBack = onBack,
        onChange = viewModel::updateRecovery,
        onRequestCode = viewModel::requestRecoveryCode,
        onSubmit = { viewModel.submitNewPassword(onBack) },
    )
}

/** Экран «Восстановление пароля»: запрос кода по электронной почте и ввод нового пароля. */
@Composable
fun RecoveryScreen(
    state: RecoveryUiState,
    onBack: () -> Unit = {},
    onChange: ((RecoveryUiState) -> RecoveryUiState) -> Unit = {},
    onRequestCode: () -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    AppScaffold(
        title = stringResource(R.string.recovery_title),
        onBack = onBack,
        bottomBar = {
            PrimaryButton(
                stringResource(if (state.codeRequested) R.string.recovery_submit else R.string.recovery_request),
                if (state.codeRequested) onSubmit else onRequestCode,
                loading = state.loading,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(stringResource(R.string.recovery_text), style = MaterialTheme.typography.bodyMedium)
            FormError(state.error)
            state.info?.let { NoticeBanner(it, tone = Tone.POSITIVE, icon = Icons.Filled.AccountBalanceWallet) }
            AppTextField(
                state.email, { value -> onChange { it.copy(email = value, emailError = null) } }, stringResource(R.string.field_email),
                error = state.emailError, keyboardType = KeyboardType.Email, enabled = !state.codeRequested,
            )
            if (state.codeRequested) {
                AppTextField(state.code, { value -> onChange { it.copy(code = value) } }, stringResource(R.string.recovery_code))
                AppTextField(
                    state.newPassword, { value -> onChange { it.copy(newPassword = value, passwordError = null) } },
                    stringResource(R.string.recovery_new_password), error = state.passwordError,
                    hint = stringResource(R.string.register_password_hint), password = true, imeAction = ImeAction.Done,
                )
            }
        }
    }
}
