package ru.finhelper.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.prefs.SecureStore
import ru.finhelper.app.data.prefs.Session
import ru.finhelper.app.data.prefs.SessionStore
import ru.finhelper.app.data.remote.ChangePasswordRequest
import ru.finhelper.app.data.remote.ClientErrorRequest
import ru.finhelper.app.data.remote.DeviceRequest
import ru.finhelper.app.data.remote.EmailRequest
import ru.finhelper.app.data.remote.FeedbackRequest
import ru.finhelper.app.data.remote.LoginRequest
import ru.finhelper.app.data.remote.NetworkModule
import ru.finhelper.app.data.remote.ProfileDto
import ru.finhelper.app.data.remote.ProfileUpdateRequest
import ru.finhelper.app.data.remote.RegisterRequest
import ru.finhelper.app.data.remote.SettingRequest
import ru.finhelper.app.data.remote.SupportRequest
import ru.finhelper.app.data.remote.TextRequest
import ru.finhelper.app.data.remote.UserCreateRequest
import ru.finhelper.app.data.repo.CacheStore
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.TransactionDraft
import ru.finhelper.app.data.sync.SyncEngine
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Проверка совместимости с настоящей серверной частью. Выполняется, только если
 * задан адрес запущенного сервера: `./gradlew testDebugUnitTest -PliveServer=http://127.0.0.1:8000/`
 * (сервер должен допускать регистрацию без подтверждения адреса электронной почты).
 * Для разделов администратора дополнительно задаются свойства окружения
 * LIVE_ADMIN_EMAIL и LIVE_ADMIN_PASSWORD.
 */
@RunWith(RobolectricTestRunner::class)
class LiveServerTest {
    private val baseUrl = System.getProperty("liveServer").orEmpty()
    private val env = TestEnvironment()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session = SessionStore(SecureStore(context.getSharedPreferences("live-secure", Context.MODE_PRIVATE), PlainCipher))
    private val network = NetworkModule(baseUrl = { baseUrl }, session = session, onSessionExpired = {})
    private val api = network.api
    private val engine = SyncEngine(env.db, env.adapters, env.settings) { api.sync(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = FinanceRepository(
        env.db, env.adapters, env.queue, CacheStore(env.db.cachedDocuments()), MutableStateFlow(ProfileDto()), scope,
    )
    private val password = "Password123"

    @Before
    fun setUp() {
        assumeTrue("Адрес сервера не задан – проверка пропущена", baseUrl.isNotBlank())
    }

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    private suspend fun login(email: String, userPassword: String) {
        val tokens = api.login(LoginRequest(email, userPassword))
        session.save(tokens.accessToken, tokens.refreshToken, Session(tokens.userId, email, tokens.role))
    }

    /** Регистрация, вход, синхронизация всех видов записей и чтение разделов пользователя. */
    @Test
    fun userScenarioAgainstRealServer() = runBlocking<Unit> {
        val email = "mobile.${System.currentTimeMillis()}@example.com"
        api.register(RegisterRequest(email, password, "Проверка приложения", consentPersonalData = true))
        login(email, password)
        assertEquals(email, api.profile().email)

        // Первая синхронизация загружает системный справочник категорий с теми же идентификаторами
        engine.sync()
        val food = SystemCategories.id("Продукты/Супермаркеты")
        assertNotNull(env.db.categories().byId(food))
        assertEquals(39, env.db.categories().count())

        val card = repository.saveAccount(null, "Карта", "bank", "RUB", BigDecimal("50000.00"))
        val usd = repository.saveAccount(null, "Доллары", "bank", "USD", BigDecimal("100.00"))
        val now = System.currentTimeMillis()
        repository.saveCategory(null, "Фермерские продукты", "expense", SystemCategories.id("Продукты"))
        val expense = repository.saveTransaction(
            TransactionDraft(kind = "expense", amount = BigDecimal("1250.50"), accountId = card.id, categoryId = food, occurredAt = now, merchant = "Пятерочка")
        )
        repository.saveTransaction(
            TransactionDraft(kind = "income", amount = BigDecimal("95000"), accountId = card.id, categoryId = SystemCategories.id("Зарплата"), occurredAt = now)
        )
        repository.saveTransaction(TransactionDraft(kind = "transfer", amount = BigDecimal("9000"), accountId = card.id, toAccountId = usd.id, occurredAt = now))
        repository.saveTemplate(null, "Обед", "expense", BigDecimal("380"), card.id, SystemCategories.id("Кафе"), "")
        repository.saveRecurringPayment(null, "Интернет", BigDecimal("650"), card.id, SystemCategories.id("Связь/Интернет"), "month", LocalDate.now().plusDays(5), 1)
        repository.setBudget(SystemCategories.id("Продукты"), "month", BigDecimal("1000"))
        repository.saveGoal(null, "Отпуск", BigDecimal("120000"), LocalDate.now().plusMonths(8), "high")
        val goal = env.db.goals().observeActive().first().single()
        repository.addContribution(goal.id, BigDecimal("12000"))
        repository.saveTrade(null, "stock", "SBER", "Сбербанк", "buy", BigDecimal("10"), BigDecimal("265.4"), BigDecimal("1.33"), "RUB", now)
        repository.saveTrade(null, "crypto", "BTC", "Биткоин", "buy", BigDecimal("0.015"), BigDecimal("6200000"), BigDecimal.ZERO, "RUB", now)
        val queued = env.queue.pendingCount.first()

        val outcome = engine.sync()

        assertEquals("Сервер отклонил операции: ${outcome.problems}", emptyList<Any>(), outcome.problems)
        assertEquals(queued, outcome.pushed)
        assertEquals(0, env.queue.pendingCount.first())
        // Записи подтверждены сервером и получили номера ревизий
        assertTrue(env.db.transactions().byId(expense.id)!!.rev > 0)
        assertEquals(0, BigDecimal("1250.50").compareTo(env.db.transactions().byId(expense.id)!!.amount))
        assertTrue(env.db.trades().observeActive().first().all { it.rev > 0 })
        // Повторная синхронизация ничего не отправляет и не меняет данные
        val again = engine.sync()
        assertEquals(0, again.pushed)
        assertEquals(3, env.db.transactions().observeActive().first().size)

        // Удаление и восстановление транзакции
        repository.deleteTransaction(expense.id)
        repository.restoreTransaction(expense.id)
        assertTrue(engine.sync().problems.isEmpty())

        // Разделы, работающие через сервер: ответы должны разбираться моделями приложения
        assertTrue(api.rates().rates.containsKey("USD"))
        val portfolio = api.portfolio()
        assertEquals(setOf("SBER", "BTC"), portfolio.positions.map { it.ticker }.toSet())
        assertTrue(api.portfolioDynamics("month").points.isNotEmpty())
        assertEquals("SBER", api.asset("SBER").ticker)
        val recommendations = api.recommendations()
        recommendations.items.firstOrNull()?.let { assertEquals("useful", api.recommendationFeedback(it.id, FeedbackRequest(true)).feedback) }
        assertEquals("RUB", api.forecast().currency)
        api.risks()
        assertNotNull(api.notifications().items)
        assertTrue(api.faq().items.isNotEmpty())
        api.suggestCategory(TextRequest("Пятерочка"), "expense")
        val parsed = api.parseReceipt("ООО АГРОТОРГ\nИТОГ 134.90".toRequestBody("text/plain".toMediaType()))
        assertEquals(0, BigDecimal("134.90").compareTo(parsed.amount))
        api.registerDevice(DeviceRequest(env.settings.deviceId, "", "JVM"))
        api.clientError(ClientErrorRequest("test", "Проверка журнала ошибок"))
        api.supportRequest(SupportRequest("Проверка", "Проверка формы обращения"))
        assertEquals("dark", api.updateProfile(ProfileUpdateRequest(theme = "dark", baseCurrency = "RUB")).theme)
        val today = LocalDate.now()
        assertTrue(api.exportTransactions("xlsx", today.minusDays(1).toString(), today.toString()).bytes().isNotEmpty())
        assertTrue(api.exportReport("pdf", today.minusDays(1).toString(), today.toString(), emptyList(), emptyList()).bytes().isNotEmpty())

        // Бюджет превышен (1250,50 при лимите 1000): сервер сформировал уведомление
        assertTrue(api.notifications().items.any { it.kind == "budget_exceeded" })

        // Импорт банковской выписки CSV: операции появляются на устройстве после синхронизации
        val statement = "Дата;Сумма;Описание\n19.11.2026;-500,00;Аптека\n19.11.2026;1200,00;Возврат".toByteArray()
        val file = MultipartBody.Part.createFormData("file", "statement.csv", statement.toRequestBody("text/csv".toMediaType()))
        val imported = api.importStatement(card.id.toRequestBody("text/plain".toMediaType()), file)
        assertEquals(2, imported.imported)
        engine.sync()
        assertEquals(5, env.db.transactions().observeActive().first().size)

        // Учетная запись: смена пароля, настройка двухфакторной аутентификации, запрос восстановления
        api.changePassword(ChangePasswordRequest(password, "NewPassword456"))
        assertTrue(api.setupTwoFactor().secret.isNotBlank())
        api.forgotPassword(EmailRequest(email))
        api.logout()
    }

    /** Разделы аналитика и администратора. */
    @Test
    fun adminScenarioAgainstRealServer() = runBlocking<Unit> {
        val email = System.getenv("LIVE_ADMIN_EMAIL").orEmpty()
        assumeTrue("Учетная запись администратора не задана – проверка пропущена", email.isNotBlank())
        login(email, System.getenv("LIVE_ADMIN_PASSWORD").orEmpty())

        api.mlQuality()
        assertNotNull(api.mlModels().items)
        assertTrue(api.mlTrainingData().total >= 0)
        assertTrue(api.adminUsers(null).items.any { it.email == email })
        assertTrue(api.adminLogs(null, null, null, "auth").items.isNotEmpty())
        assertTrue(api.adminExportLogs(null, null, null, null).bytes().isNotEmpty())
        assertTrue(api.adminMonitoring(24).availabilityPercent >= 0)
        assertNotNull(api.adminErrors(24).items)
        val settings = api.adminSettings()
        assertTrue(settings.containsKey("maintenance"))
        api.adminPutSetting("notifications", SettingRequest(settings.getValue("notifications")))

        // Управление учетными записями: создание с ролью, блокировка, разблокировка, сброс пароля
        val created = api.adminCreateUser(UserCreateRequest("analyst.${System.currentTimeMillis()}@example.com", "Аналитик", "analyst"))
        assertNotNull(created.temporaryPassword)
        assertTrue(api.adminBlockUser(created.id).isBlocked)
        assertTrue(!api.adminUnblockUser(created.id).isBlocked)
        assertNotNull(api.adminResetPassword(created.id).temporaryPassword)
        assertTrue(api.adminUsers("analyst.").items.any { it.id == created.id && it.role == "analyst" })

        // Запуск обучения модели и опрос ее состояния
        val model = api.mlTrain()
        assertTrue(model.status in setOf("training", "ready", "failed"))
        var state = model
        var attempts = 0
        while (state.status == "training" && attempts++ < 60) {
            delay(2_000)
            state = api.mlModels().items.first { it.id == model.id }
        }
        assertTrue("Обучение не завершилось за отведенное время", state.status != "training")
    }
}
