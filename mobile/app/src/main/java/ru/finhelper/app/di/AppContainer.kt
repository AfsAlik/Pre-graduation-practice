package ru.finhelper.app.di

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.finhelper.app.BuildConfig
import ru.finhelper.app.R
import ru.finhelper.app.data.local.AppDatabase
import ru.finhelper.app.data.local.DatabaseFactory
import ru.finhelper.app.data.local.EncryptedDatabaseFactory
import ru.finhelper.app.data.local.NotificationEntity
import ru.finhelper.app.data.prefs.KeystoreCipher
import ru.finhelper.app.data.prefs.SecretCipher
import ru.finhelper.app.data.prefs.SecureStore
import ru.finhelper.app.data.prefs.SessionStore
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.NetworkModule
import ru.finhelper.app.data.repo.AuthRepository
import ru.finhelper.app.data.repo.CacheStore
import ru.finhelper.app.data.repo.ErrorLogger
import ru.finhelper.app.data.repo.FileRepository
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.InsightsRepository
import ru.finhelper.app.data.repo.InvestmentRepository
import ru.finhelper.app.data.repo.ProfileRepository
import ru.finhelper.app.data.repo.ReceiptRecognizer
import ru.finhelper.app.data.repo.ReferenceRepository
import ru.finhelper.app.data.sync.AndroidConnectivityObserver
import ru.finhelper.app.data.sync.ConnectivityObserver
import ru.finhelper.app.data.sync.SyncAdapters
import ru.finhelper.app.data.sync.SyncEngine
import ru.finhelper.app.data.sync.SyncManager
import ru.finhelper.app.data.sync.SyncProblem
import ru.finhelper.app.data.sync.SyncQueue
import ru.finhelper.app.data.sync.SyncWorker
import ru.finhelper.app.notify.LocalAlerts
import ru.finhelper.app.notify.Notifier
import ru.finhelper.app.notify.PushSetup
import ru.finhelper.app.notify.ServerNotifications

/** Сообщение пользователю: готовый текст либо строковый ресурс с параметрами. */
data class AppMessage(val text: String? = null, @StringRes val resource: Int = 0, val arguments: List<Any> = emptyList())

/** Сообщения о результате действий, показываемые пользователю всплывающей строкой (п. 4.4.5.35 ТЗ). */
class AppMessages {
    private val channel = Channel<AppMessage>(Channel.BUFFERED)
    val flow: Flow<AppMessage> = channel.receiveAsFlow()

    fun show(text: String) {
        channel.trySend(AppMessage(text = text))
    }

    fun show(@StringRes resource: Int, vararg arguments: Any) {
        channel.trySend(AppMessage(resource = resource, arguments = arguments.toList()))
    }
}

/**
 * Контейнер зависимостей приложения: создает и связывает хранилища, сетевой слой,
 * синхронизацию и репозитории. Объекты создаются один раз на время жизни процесса.
 *
 * Параметры конструктора позволяют подменить в тестах шифрование, базу данных и
 * источник сведений о сети.
 */
class AppContainer(
    private val context: Context,
    cipher: SecretCipher = KeystoreCipher(),
    databaseFactory: DatabaseFactory? = null,
    connectivityObserver: ConnectivityObserver? = null,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val messages = AppMessages()

    val settings = SettingsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE), BuildConfig.DEFAULT_BASE_URL)
    private val secureStore = SecureStore(context.getSharedPreferences("secure", Context.MODE_PRIVATE), cipher)
    val session = SessionStore(secureStore)
    val database: AppDatabase = (databaseFactory ?: EncryptedDatabaseFactory(secureStore)).create(context)
    val connectivity: ConnectivityObserver = connectivityObserver ?: AndroidConnectivityObserver(context)

    private val network = NetworkModule(
        baseUrl = { settings.baseUrl },
        session = session,
        onSessionExpired = {
            session.clear()
            messages.show(R.string.session_expired)
        },
        logging = BuildConfig.DEBUG,
    )
    val api: FinHelperApi = network.api

    val cache = CacheStore(database.cachedDocuments())
    val errorLogger = ErrorLogger(database.clientErrors(), api, appScope)
    val profiles = ProfileRepository(api, settings)
    val notifier = Notifier(context)

    private val adapters = SyncAdapters(database)
    private val queue = SyncQueue(database)
    private val engine = SyncEngine(database, adapters, settings) { request -> api.sync(request) }

    val finance = FinanceRepository(
        db = database, adapters = adapters, queue = queue, cache = cache, profile = profiles.profile, scope = appScope,
        onChanged = ::onDataChanged,
    )
    val investments = InvestmentRepository(api, cache)
    val insights = InsightsRepository(api, cache)
    val reference = ReferenceRepository(api, cache)
    val files = FileRepository(context, api)
    val receiptRecognizer = ReceiptRecognizer(context)
    val auth = AuthRepository(api, session, settings, database, profiles) { PushSetup.token(context) }

    val localAlerts = LocalAlerts({ finance.snapshot.value }, database.recurringPayments(), database.notifications(), settings, notifier)
    private val serverNotifications = ServerNotifications(api, database.notifications(), notifier) {
        profiles.profile.value.notificationSettings.pushEnabled
    }

    val syncManager = SyncManager(
        engine = engine, queue = queue, connectivity = connectivity, session = session, settings = settings, scope = appScope,
        afterSync = ::afterSync, onProblems = ::reportSyncProblems,
        onError = { error ->
            errorLogger.log(error, "sync")
            SyncWorker.enqueue(context)
        },
    )

    /** Запускает фоновые процессы приложения; вызывается один раз при старте. */
    @OptIn(FlowPreview::class)
    fun start() {
        PushSetup.initialize(context)
        SyncWorker.schedulePeriodic(context)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            errorLogger.log(error, "uncaught:${thread.name}")
            previousHandler?.uncaughtException(thread, error)
        }
        appScope.launch {
            if (session.session.value != null) auth.seedSystemCategories()
            syncManager.syncNow()
        }
        // Условия локальных уведомлений проверяются после каждого изменения данных
        appScope.launch {
            finance.snapshot.debounce(ALERT_DEBOUNCE_MS).collect { runCatching { localAlerts.check() } }
        }
    }

    /** После изменения данных: при наличии сети – синхронизация сразу, иначе – фоновая задача до появления сети. */
    private fun onDataChanged() {
        if (connectivity.online.value) syncManager.requestSync() else SyncWorker.enqueue(context)
    }

    private suspend fun afterSync() {
        profiles.refresh()
        reference.refreshRates()
        serverNotifications.pull()
        errorLogger.upload()
        finance.purgeTrash()
    }

    /** Сообщает пользователю об операциях, не принятых сервером (п. 4.1.5.3 ТЗ). */
    private suspend fun reportSyncProblems(problems: List<SyncProblem>) {
        val reason = problems.first().message.ifBlank { "запись изменена на другом устройстве" }
        val text = "Сервер не принял изменений: ${problems.size}. Причина: $reason. Загружена версия с сервера."
        messages.show(text)
        database.notifications().upsert(
            NotificationEntity("sync:${System.currentTimeMillis()}", "sync_conflict", "Конфликт при синхронизации", text, System.currentTimeMillis())
        )
        errorLogger.log("sync_conflict", text, problems.joinToString { "${it.entity}:${it.entityId}:${it.status}" })
    }

    private companion object {
        const val ALERT_DEBOUNCE_MS = 1_000L
    }
}
