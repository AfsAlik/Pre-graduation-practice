package ru.finhelper.app.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.finhelper.app.data.prefs.SessionStore
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.remote.ApiError

/**
 * Состояние синхронизации для индикатора, постоянно отображаемого в интерфейсе
 * (п. 4.2.1.6 ж, 4.4.5.33 ТЗ).
 */
data class SyncStatus(
    val online: Boolean = true,
    /** Число операций, ожидающих отправки. */
    val pending: Int = 0,
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0,
    /** Сервер находится в режиме технического обслуживания. */
    val maintenance: Boolean = false,
    /** Текст ошибки последней попытки синхронизации либо null. */
    val error: String? = null,
)

/**
 * Управление синхронизацией: запуск при изменении данных и при восстановлении
 * связи, ведение состояния для интерфейса. Синхронизация выполняется в фоновой
 * сопрограмме и не блокирует ввод данных (п. 4.2.3.3 ТЗ).
 *
 * @param afterSync действия после успешного сеанса: обновление курсов валют,
 * профиля и уведомлений, проверка локальных напоминаний
 * @param onProblems оповещение пользователя об операциях, не принятых сервером
 */
class SyncManager(
    private val engine: SyncEngine,
    queue: SyncQueue,
    connectivity: ConnectivityObserver,
    private val session: SessionStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val afterSync: suspend () -> Unit = {},
    private val onProblems: suspend (List<SyncProblem>) -> Unit = {},
    private val onError: suspend (Throwable) -> Unit = {},
) {
    private data class Progress(val syncing: Boolean = false, val maintenance: Boolean = false, val error: String? = null, val lastSyncAt: Long = 0)

    private val progress = MutableStateFlow(Progress(lastSyncAt = settings.lastSyncAt))

    val status: StateFlow<SyncStatus> = combine(connectivity.online, queue.pendingCount, progress) { online, pending, state ->
        SyncStatus(online, pending, state.syncing, state.lastSyncAt, state.maintenance, state.error)
    }.stateIn(scope, SharingStarted.Eagerly, SyncStatus(online = connectivity.online.value, lastSyncAt = settings.lastSyncAt))

    init {
        // Автоматическая синхронизация при восстановлении подключения
        scope.launch {
            connectivity.online.drop(1).filter { it }.collect { requestSync() }
        }
    }

    /** Запускает синхронизацию в фоне, если выполнен вход и сеанс еще не идет. */
    fun requestSync() {
        scope.launch { syncNow() }
    }

    /**
     * Выполняет сеанс синхронизации и возвращает true при успехе. Ошибка сети не
     * приводит к потере данных: операции остаются в очереди до следующей попытки.
     */
    suspend fun syncNow(): Boolean {
        if (session.session.value == null || progress.value.syncing) return false
        progress.value = progress.value.copy(syncing = true)
        return try {
            val outcome = engine.sync()
            settings.lastSyncAt = System.currentTimeMillis()
            progress.value = Progress(lastSyncAt = settings.lastSyncAt)
            if (outcome.problems.isNotEmpty()) onProblems(outcome.problems)
            runCatching { afterSync() }
            true
        } catch (error: CancellationException) {
            progress.value = progress.value.copy(syncing = false)
            throw error
        } catch (error: Exception) {
            val apiError = ApiError.from(error)
            progress.value = progress.value.copy(
                syncing = false,
                maintenance = apiError.isMaintenance,
                // Отсутствие сети отображается индикатором «Офлайн», а не ошибкой
                error = if (apiError.isOffline || apiError.isMaintenance) null else apiError.message,
            )
            if (!apiError.isOffline && !apiError.isMaintenance) onError(error)
            false
        }
    }
}
