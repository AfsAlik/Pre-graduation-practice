package ru.finhelper.app.ui.admin

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import ru.finhelper.app.R
import ru.finhelper.app.data.prefs.SessionStore
import ru.finhelper.app.data.remote.AdminUserDto
import ru.finhelper.app.data.remote.AuditLogDto
import ru.finhelper.app.data.remote.ErrorLogDto
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.MlModelDto
import ru.finhelper.app.data.remote.MonitoringDto
import ru.finhelper.app.data.remote.QualityDto
import ru.finhelper.app.data.remote.SettingRequest
import ru.finhelper.app.data.remote.TrainingDataDto
import ru.finhelper.app.data.remote.UserCreateRequest
import ru.finhelper.app.data.remote.apiCall
import ru.finhelper.app.data.remote.userMessage
import ru.finhelper.app.data.repo.FileRepository
import ru.finhelper.app.di.AppMessages
import java.time.LocalDate

/** Состояние экрана «Аналитика ИИ». */
data class AiAnalyticsUiState(val loading: Boolean = true, val quality: QualityDto? = null, val error: String? = null)

/** Состояние экрана «Управление моделями ML». */
data class ModelsUiState(
    val loading: Boolean = true,
    val models: List<MlModelDto> = emptyList(),
    val trainingData: TrainingDataDto? = null,
    /** Выполняется запрос запуска обучения или активации версии. */
    val busy: Boolean = false,
    val error: String? = null,
)

/** Состояние экрана «Пользователи». */
data class UsersUiState(
    val loading: Boolean = true,
    val query: String = "",
    val users: List<AdminUserDto> = emptyList(),
    /** Временный пароль, выданный при создании учетной записи или сбросе пароля. */
    val temporaryPassword: Pair<String, String>? = null,
    val error: String? = null,
)

/** Условия отбора записей журнала аудита (п. 4.2.1.8 ж ТЗ). */
data class LogFilter(val user: String = "", val action: String = "", val from: LocalDate? = null, val to: LocalDate? = null)

/** Состояние экрана «Логи». */
data class LogsUiState(
    val loading: Boolean = true,
    val filter: LogFilter = LogFilter(),
    val items: List<AuditLogDto> = emptyList(),
    val total: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Состояние экрана «Мониторинг». */
data class MonitoringUiState(
    val loading: Boolean = true,
    /** Период в часах: 24, 168 (7 дней) или 720 (30 дней). */
    val hours: Int = 24,
    val summary: MonitoringDto? = null,
    val errors: List<ErrorLogDto> = emptyList(),
    val error: String? = null,
)

/** Состояние экрана «Настройки системы»: раздел -> параметры. */
data class SystemSettingsUiState(val loading: Boolean = true, val settings: Map<String, JsonObject> = emptyMap(), val error: String? = null)

/**
 * Административный модуль (п. 4.1.1.10, 4.2.1.5 ж–з, 4.2.1.8 е–и ТЗ). Все функции
 * выполняются сервером и требуют подключения к сети; права проверяет сервер.
 */
class AdminViewModel(
    private val api: FinHelperApi,
    private val files: FileRepository,
    session: SessionStore,
    private val messages: AppMessages,
) : ViewModel() {
    /** Доступны ли разделы администратора (иначе – только разделы аналитика). */
    val isAdmin: Boolean = session.session.value?.isAdmin == true

    private val qualityState = MutableStateFlow(AiAnalyticsUiState())
    val quality: StateFlow<AiAnalyticsUiState> = qualityState

    private val modelsState = MutableStateFlow(ModelsUiState())
    val models: StateFlow<ModelsUiState> = modelsState

    private val usersState = MutableStateFlow(UsersUiState())
    val users: StateFlow<UsersUiState> = usersState

    private val logsState = MutableStateFlow(LogsUiState())
    val logs: StateFlow<LogsUiState> = logsState

    private val monitoringState = MutableStateFlow(MonitoringUiState())
    val monitoring: StateFlow<MonitoringUiState> = monitoringState

    private val settingsState = MutableStateFlow(SystemSettingsUiState())
    val settings: StateFlow<SystemSettingsUiState> = settingsState

    private var pollingJob: Job? = null

    // ------------------------------------------------------------ аналитика ИИ
    fun loadQuality() {
        qualityState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            apiCall { api.mlQuality() }
                .onSuccess { qualityState.value = AiAnalyticsUiState(loading = false, quality = it) }
                .onFailure { error -> qualityState.update { it.copy(loading = false, error = error.userMessage()) } }
        }
    }

    // ------------------------------------------------------------ модели
    fun loadModels() {
        viewModelScope.launch { refreshModels() }
    }

    private suspend fun refreshModels() {
        val models = apiCall { api.mlModels() }
        val data = apiCall { api.mlTrainingData() }.getOrNull()
        modelsState.update {
            it.copy(
                loading = false, models = models.getOrNull()?.items ?: it.models,
                trainingData = data ?: it.trainingData, error = models.exceptionOrNull()?.userMessage(),
            )
        }
        if (modelsState.value.models.any { it.status == STATUS_TRAINING }) startPolling()
    }

    /** Пока идет обучение, состояние моделей запрашивается каждые несколько секунд. */
    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (modelsState.value.models.any { it.status == STATUS_TRAINING }) {
                delay(POLL_INTERVAL_MS)
                val before = modelsState.value.models.count { it.status == STATUS_TRAINING }
                refreshModels()
                if (modelsState.value.models.count { it.status == STATUS_TRAINING } < before) messages.show(R.string.models_training_finished)
            }
        }
    }

    /** Запуск повторного обучения модели категоризации (п. 4.2.1.5 з ТЗ). */
    fun startTraining() = modelAction(R.string.models_training_started) { api.mlTrain() }

    fun activate(id: String) = modelAction(R.string.models_activated) { api.mlActivate(id) }

    private fun modelAction(success: Int, call: suspend () -> MlModelDto) {
        modelsState.update { it.copy(busy = true) }
        viewModelScope.launch {
            apiCall { call() }.onSuccess { messages.show(success) }.onFailure { messages.show(it.userMessage()) }
            refreshModels()
            modelsState.update { it.copy(busy = false) }
        }
    }

    // ------------------------------------------------------------ пользователи
    fun loadUsers(query: String = usersState.value.query) {
        usersState.update { it.copy(query = query, loading = true, error = null) }
        viewModelScope.launch {
            apiCall { api.adminUsers(query.trim().ifEmpty { null }) }
                .onSuccess { page -> usersState.update { it.copy(loading = false, users = page.items) } }
                .onFailure { error -> usersState.update { it.copy(loading = false, error = error.userMessage()) } }
        }
    }

    fun createUser(email: String, name: String, role: String, password: String) = userAction(R.string.users_created) {
        api.adminCreateUser(UserCreateRequest(email.trim(), name.trim(), role, password.ifBlank { null }))
    }

    fun setBlocked(user: AdminUserDto, blocked: Boolean) = userAction(if (blocked) R.string.users_blocked else R.string.users_unblocked) {
        if (blocked) api.adminBlockUser(user.id) else api.adminUnblockUser(user.id)
    }

    fun resetPassword(user: AdminUserDto) = userAction(R.string.users_password_reset) { api.adminResetPassword(user.id) }

    fun dismissTemporaryPassword() = usersState.update { it.copy(temporaryPassword = null) }

    private fun userAction(success: Int, call: suspend () -> AdminUserDto) {
        viewModelScope.launch {
            apiCall { call() }.onSuccess { user ->
                messages.show(success)
                user.temporaryPassword?.let { password -> usersState.update { it.copy(temporaryPassword = user.email to password) } }
                loadUsers()
            }.onFailure { messages.show(it.userMessage()) }
        }
    }

    // ------------------------------------------------------------ журнал аудита
    fun updateLogFilter(transform: (LogFilter) -> LogFilter) = logsState.update { it.copy(filter = transform(it.filter)) }

    fun loadLogs() {
        val filter = logsState.value.filter
        logsState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            apiCall { api.adminLogs(filter.from?.toString(), filter.to?.toString(), filter.user.trim().ifEmpty { null }, filter.action.trim().ifEmpty { null }) }
                .onSuccess { page -> logsState.update { it.copy(loading = false, items = page.items, total = page.total) } }
                .onFailure { error -> logsState.update { it.copy(loading = false, error = error.userMessage()) } }
        }
    }

    fun exportLogs(target: Uri) = logsFileAction {
        val filter = logsState.value.filter
        files.exportLogs(target, filter.from?.toString(), filter.to?.toString(), filter.user.trim().ifEmpty { null }, filter.action.trim().ifEmpty { null })
            .onSuccess { messages.show(R.string.file_saved) }
    }

    fun importLogs(source: Uri) = logsFileAction {
        files.importLogs(source).onSuccess { count ->
            messages.show(R.string.logs_imported, count)
            loadLogs()
        }
    }

    private fun logsFileAction(action: suspend () -> Result<*>) {
        logsState.update { it.copy(busy = true) }
        viewModelScope.launch {
            action().onFailure { messages.show(it.userMessage()) }
            logsState.update { it.copy(busy = false) }
        }
    }

    // ------------------------------------------------------------ мониторинг
    fun loadMonitoring(hours: Int = monitoringState.value.hours) {
        monitoringState.update { it.copy(hours = hours, loading = true, error = null) }
        viewModelScope.launch {
            val summary = apiCall { api.adminMonitoring(hours) }
            val errors = apiCall { api.adminErrors(hours) }.getOrNull()?.items.orEmpty()
            monitoringState.update {
                it.copy(loading = false, summary = summary.getOrNull() ?: it.summary, errors = errors, error = summary.exceptionOrNull()?.userMessage())
            }
        }
    }

    // ------------------------------------------------------------ настройки системы
    fun loadSettings() {
        viewModelScope.launch {
            apiCall { api.adminSettings() }
                .onSuccess { settingsState.value = SystemSettingsUiState(loading = false, settings = it) }
                .onFailure { error -> settingsState.update { it.copy(loading = false, error = error.userMessage()) } }
        }
    }

    fun saveSetting(key: String, value: JsonObject) {
        viewModelScope.launch {
            apiCall { api.adminPutSetting(key, SettingRequest(value)) }
                .onSuccess {
                    messages.show(R.string.settings_saved)
                    loadSettings()
                }
                .onFailure { messages.show(it.userMessage()) }
        }
    }

    companion object {
        const val STATUS_TRAINING = "training"
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
