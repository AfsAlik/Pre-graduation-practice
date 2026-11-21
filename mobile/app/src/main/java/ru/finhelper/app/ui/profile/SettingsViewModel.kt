package ru.finhelper.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finhelper.app.R
import ru.finhelper.app.data.local.NotificationDao
import ru.finhelper.app.data.local.NotificationEntity
import ru.finhelper.app.data.prefs.SessionStore
import ru.finhelper.app.data.remote.FaqItem
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.NotificationSettingsDto
import ru.finhelper.app.data.remote.ProfileDto
import ru.finhelper.app.data.remote.SupportRequest
import ru.finhelper.app.data.remote.apiCall
import ru.finhelper.app.data.remote.userMessage
import ru.finhelper.app.data.repo.AuthRepository
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.ProfileRepository
import ru.finhelper.app.data.repo.ReferenceRepository
import ru.finhelper.app.data.sync.SyncManager
import ru.finhelper.app.di.AppMessages
import ru.finhelper.app.ui.auth.AuthValidation

/** Состояние экрана «Профиль». */
data class ProfileUiState(
    val profile: ProfileDto = ProfileDto(),
    val email: String = "",
    val currencies: List<String> = listOf("RUB"),
    /** Число операций, еще не отправленных на сервер (о них предупреждает диалог выхода). */
    val pendingOperations: Int = 0,
)

/** Состояние экрана «Безопасность». */
data class SecurityUiState(
    val totpEnabled: Boolean = false,
    /** Секрет для приложения-аутентификатора; показывается после начала настройки. */
    val totpSecret: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Состояние экрана «Справка». */
data class HelpUiState(val faq: List<FaqItem> = emptyList(), val sending: Boolean = false, val error: String? = null)

/**
 * Профиль и настройки пользователя (п. 4.2.1.8 г, к; 4.2.1.6 в ТЗ): базовая валюта,
 * риск-профиль, тема, уведомления, безопасность, справка и поддержка.
 */
class SettingsViewModel(
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val reference: ReferenceRepository,
    private val api: FinHelperApi,
    finance: FinanceRepository,
    session: SessionStore,
    syncManager: SyncManager,
    private val notificationDao: NotificationDao,
    private val messages: AppMessages,
) : ViewModel() {
    val profile: StateFlow<ProfileUiState> = combine(
        profiles.profile, session.session, finance.snapshot, syncManager.status,
    ) { profile, user, snapshot, status ->
        ProfileUiState(profile, user?.email.orEmpty(), snapshot.converter.currencies, status.pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState(profiles.profile.value))

    private val securityState = MutableStateFlow(SecurityUiState(totpEnabled = profiles.profile.value.totpEnabled))
    val security: StateFlow<SecurityUiState> = securityState

    private val helpState = MutableStateFlow(HelpUiState())
    val help: StateFlow<HelpUiState> = helpState

    val notifications: StateFlow<List<NotificationEntity>> = notificationDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            profiles.refresh()
            securityState.update { it.copy(totpEnabled = profiles.profile.value.totpEnabled) }
        }
    }

    /** Изменяет настройку профиля: она применяется сразу и передается на сервер при наличии сети. */
    fun updateProfile(transform: (ProfileDto) -> ProfileDto) {
        viewModelScope.launch {
            profiles.update(transform)
            messages.show(R.string.settings_saved)
        }
    }

    fun updateNotifications(transform: (NotificationSettingsDto) -> NotificationSettingsDto) =
        updateProfile { it.copy(notificationSettings = transform(it.notificationSettings)) }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            auth.logout()
            onDone()
        }
    }

    // ------------------------------------------------------------ безопасность
    private fun securityCall(success: Int? = null, onSuccess: () -> Unit = {}, call: suspend () -> Result<*>) {
        securityState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            call().onSuccess {
                success?.let(messages::show)
                onSuccess()
            }.onFailure { error -> securityState.update { it.copy(error = error.userMessage()) } }
            securityState.update { it.copy(busy = false, totpEnabled = profiles.profile.value.totpEnabled) }
        }
    }

    /** Начинает настройку двухфакторной аутентификации: сервер выдает секрет. */
    fun startTwoFactorSetup() = securityCall {
        auth.setupTwoFactor().onSuccess { setup -> securityState.update { it.copy(totpSecret = setup.secret) } }
    }

    fun enableTwoFactor(code: String) = securityCall(R.string.security_2fa_enabled, { securityState.update { it.copy(totpSecret = null) } }) {
        auth.enableTwoFactor(code)
    }

    fun disableTwoFactor(code: String) = securityCall(R.string.security_2fa_disabled) { auth.disableTwoFactor(code) }

    fun changePassword(current: String, new: String) {
        AuthValidation.passwordError(new)?.let { error ->
            securityState.update { it.copy(error = error) }
            return
        }
        securityCall(R.string.security_password_changed) { auth.changePassword(current, new) }
    }

    fun deleteAccount(password: String, onDone: () -> Unit) = securityCall(R.string.security_account_deleted, onDone) { auth.deleteAccount(password) }

    // ------------------------------------------------------------ справка и поддержка
    fun loadFaq() {
        viewModelScope.launch {
            reference.faq().onSuccess { cached -> helpState.update { it.copy(faq = cached.value.items) } }
                .onFailure { error -> helpState.update { it.copy(error = error.userMessage()) } }
        }
    }

    fun sendSupportRequest(subject: String, message: String, onDone: () -> Unit) {
        helpState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            apiCall { api.supportRequest(SupportRequest(subject.trim(), message.trim())) }.onSuccess {
                messages.show(R.string.help_request_sent)
                onDone()
            }.onFailure { error -> helpState.update { it.copy(error = error.userMessage()) } }
            helpState.update { it.copy(sending = false) }
        }
    }

    fun markNotificationsRead() {
        viewModelScope.launch { notificationDao.markAllRead() }
    }
}
