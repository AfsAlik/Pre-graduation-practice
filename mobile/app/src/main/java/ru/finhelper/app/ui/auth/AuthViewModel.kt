package ru.finhelper.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finhelper.app.R
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.remote.ApiError
import ru.finhelper.app.data.remote.userMessage
import ru.finhelper.app.data.repo.AuthRepository
import ru.finhelper.app.data.sync.SyncManager
import ru.finhelper.app.di.AppMessages

/** Состояние экрана входа. */
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val totpCode: String = "",
    /** Сервер запросил код двухфакторной аутентификации. */
    val twoFactorRequired: Boolean = false,
    val serverUrl: String = "",
    val loading: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    /** Ошибка входа, полученная от сервера. */
    val error: String? = null,
)

/** Состояние экрана регистрации. */
data class RegisterUiState(
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val passwordRepeat: String = "",
    /** Согласие на обработку персональных данных (обязательно, п. 4.2.1.8 а ТЗ). */
    val consent: Boolean = false,
    val loading: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val repeatError: String? = null,
    val consentError: Boolean = false,
    val error: String? = null,
)

/** Состояние экрана восстановления пароля. */
data class RecoveryUiState(
    val email: String = "",
    /** Код из письма; поле показывается после отправки запроса. */
    val code: String = "",
    val newPassword: String = "",
    val codeRequested: Boolean = false,
    val loading: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val info: String? = null,
    val error: String? = null,
)

/** Правила проверки полей, общие для экранов учетной записи. */
object AuthValidation {
    private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    const val EMAIL_ERROR = "Введите адрес в формате name@example.com"
    const val PASSWORD_ERROR = "Не менее 8 символов, включая буквы и цифры"

    fun emailError(email: String): String? = if (EMAIL.matches(email.trim())) null else EMAIL_ERROR

    /** Требования к паролю совпадают с серверными: длина от 8 символов, буквы и цифры. */
    fun passwordError(password: String): String? =
        if (password.length < 8 || password.all(Char::isDigit) || password.all(Char::isLetter)) PASSWORD_ERROR else null
}

/** Вход, регистрация и восстановление пароля (п. 4.2.1.8 а, б ТЗ). */
class AuthViewModel(
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    private val syncManager: SyncManager,
    private val messages: AppMessages,
) : ViewModel() {
    private val loginState = MutableStateFlow(LoginUiState(serverUrl = settings.baseUrl))
    val login: StateFlow<LoginUiState> = loginState

    private val registerState = MutableStateFlow(RegisterUiState())
    val register: StateFlow<RegisterUiState> = registerState

    private val recoveryState = MutableStateFlow(RecoveryUiState())
    val recovery: StateFlow<RecoveryUiState> = recoveryState

    fun updateLogin(transform: (LoginUiState) -> LoginUiState) = loginState.update { transform(it).copy(error = null) }

    fun updateRegister(transform: (RegisterUiState) -> RegisterUiState) = registerState.update { transform(it).copy(error = null) }

    fun updateRecovery(transform: (RecoveryUiState) -> RecoveryUiState) = recoveryState.update { transform(it).copy(error = null) }

    /** Выполняет вход; при успехе запускается первая синхронизация. */
    fun submitLogin(onSuccess: () -> Unit) {
        val state = loginState.value
        val emailError = AuthValidation.emailError(state.email)
        val passwordError = if (state.password.isEmpty()) "Введите пароль" else null
        if (emailError != null || passwordError != null) {
            loginState.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        settings.baseUrl = state.serverUrl
        loginState.update { it.copy(loading = true, emailError = null, passwordError = null, error = null) }
        viewModelScope.launch {
            auth.login(state.email, state.password, state.totpCode).onSuccess {
                loginState.value = LoginUiState(serverUrl = settings.baseUrl)
                syncManager.requestSync()
                onSuccess()
            }.onFailure { error ->
                val apiError = ApiError.from(error)
                loginState.update {
                    it.copy(loading = false, error = apiError.message, twoFactorRequired = it.twoFactorRequired || apiError.twoFactorRequired)
                }
            }
        }
    }

    fun submitRegister(onSuccess: () -> Unit) {
        val state = registerState.value
        val emailError = AuthValidation.emailError(state.email)
        val passwordError = AuthValidation.passwordError(state.password)
        val repeatError = if (state.password != state.passwordRepeat) "Пароли не совпадают" else null
        if (emailError != null || passwordError != null || repeatError != null || !state.consent) {
            registerState.update {
                it.copy(emailError = emailError, passwordError = passwordError, repeatError = repeatError, consentError = !state.consent)
            }
            return
        }
        registerState.update { it.copy(loading = true, emailError = null, passwordError = null, repeatError = null, consentError = false) }
        viewModelScope.launch {
            auth.register(state.email, state.password, state.displayName, state.consent).onSuccess { message ->
                messages.show(message.ifBlank { "Учетная запись создана" })
                loginState.update { it.copy(email = state.email.trim()) }
                registerState.value = RegisterUiState()
                onSuccess()
            }.onFailure { error -> registerState.update { it.copy(loading = false, error = error.userMessage()) } }
        }
    }

    /** Запрашивает письмо с кодом восстановления пароля. */
    fun requestRecoveryCode() {
        val state = recoveryState.value
        AuthValidation.emailError(state.email)?.let { error ->
            recoveryState.update { it.copy(emailError = error) }
            return
        }
        recoveryState.update { it.copy(loading = true, emailError = null) }
        viewModelScope.launch {
            auth.forgotPassword(state.email).onSuccess { message ->
                recoveryState.update { it.copy(loading = false, codeRequested = true, info = message) }
            }.onFailure { error -> recoveryState.update { it.copy(loading = false, error = error.userMessage()) } }
        }
    }

    fun submitNewPassword(onSuccess: () -> Unit) {
        val state = recoveryState.value
        AuthValidation.passwordError(state.newPassword)?.let { error ->
            recoveryState.update { it.copy(passwordError = error) }
            return
        }
        recoveryState.update { it.copy(loading = true, passwordError = null) }
        viewModelScope.launch {
            auth.resetPassword(state.code, state.newPassword).onSuccess {
                messages.show(R.string.recovery_done)
                recoveryState.value = RecoveryUiState()
                onSuccess()
            }.onFailure { error -> recoveryState.update { it.copy(loading = false, error = error.userMessage()) } }
        }
    }

    fun completeOnboarding() {
        settings.onboardingDone = true
    }
}
