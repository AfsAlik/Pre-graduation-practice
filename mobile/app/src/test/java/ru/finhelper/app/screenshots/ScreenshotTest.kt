package ru.finhelper.app.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.math.BigDecimal
import java.time.ZoneOffset
import kotlin.math.sin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finhelper.app.DemoData
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.prefs.Session
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.remote.AdminUserDto
import ru.finhelper.app.data.remote.AssetDto
import ru.finhelper.app.data.remote.AuditLogDto
import ru.finhelper.app.data.remote.AvailabilityPointDto
import ru.finhelper.app.data.remote.ErrorLogDto
import ru.finhelper.app.data.remote.ForecastDto
import ru.finhelper.app.data.remote.ForecastValueDto
import ru.finhelper.app.data.remote.MlModelDto
import ru.finhelper.app.data.remote.MonitoringDto
import ru.finhelper.app.data.remote.PricePointDto
import ru.finhelper.app.data.remote.QualityDto
import ru.finhelper.app.data.remote.QualityPointDto
import ru.finhelper.app.data.remote.RecommendationDto
import ru.finhelper.app.data.remote.RiskDto
import ru.finhelper.app.data.remote.TrainingDataDto
import ru.finhelper.app.data.remote.ValuePointDto
import ru.finhelper.app.data.sync.SyncStatus
import ru.finhelper.app.ui.admin.AdminMenuScreen
import ru.finhelper.app.ui.admin.AiAnalyticsScreen
import ru.finhelper.app.ui.admin.AiAnalyticsUiState
import ru.finhelper.app.ui.admin.LogFilter
import ru.finhelper.app.ui.admin.LogsScreen
import ru.finhelper.app.ui.admin.LogsUiState
import ru.finhelper.app.ui.admin.ModelsScreen
import ru.finhelper.app.ui.admin.ModelsUiState
import ru.finhelper.app.ui.admin.MonitoringScreen
import ru.finhelper.app.ui.admin.MonitoringUiState
import ru.finhelper.app.ui.admin.SystemSettingsScreen
import ru.finhelper.app.ui.admin.SystemSettingsUiState
import ru.finhelper.app.ui.admin.UsersScreen
import ru.finhelper.app.ui.admin.UsersUiState
import ru.finhelper.app.ui.auth.LoginScreen
import ru.finhelper.app.ui.auth.LoginUiState
import ru.finhelper.app.ui.auth.OnboardingScreen
import ru.finhelper.app.ui.auth.RegisterScreen
import ru.finhelper.app.ui.auth.RegisterUiState
import ru.finhelper.app.ui.budget.BudgetScreen
import ru.finhelper.app.ui.budget.buildBudgetState
import ru.finhelper.app.ui.components.LocalSyncStatus
import ru.finhelper.app.ui.dashboard.DashboardScreen
import ru.finhelper.app.ui.dashboard.buildDashboardState
import ru.finhelper.app.ui.goals.GoalsScreen
import ru.finhelper.app.ui.goals.buildGoalsState
import ru.finhelper.app.ui.investments.AssetScreen
import ru.finhelper.app.ui.investments.InvestmentsScreen
import ru.finhelper.app.ui.investments.InvestmentsUiState
import ru.finhelper.app.ui.investments.buildAssetState
import ru.finhelper.app.ui.investments.buildPortfolio
import ru.finhelper.app.ui.profile.NotificationSettingsScreen
import ru.finhelper.app.ui.profile.ProfileScreen
import ru.finhelper.app.ui.profile.ProfileUiState
import ru.finhelper.app.ui.recommendations.RecommendationsScreen
import ru.finhelper.app.ui.recommendations.RecommendationsUiState
import ru.finhelper.app.ui.reports.ReportFilter
import ru.finhelper.app.ui.reports.ReportsScreen
import ru.finhelper.app.ui.reports.buildReportsState
import ru.finhelper.app.ui.theme.FinHelperTheme
import ru.finhelper.app.ui.transactions.AccountsScreen
import ru.finhelper.app.ui.transactions.RecurringScreen
import ru.finhelper.app.ui.transactions.TransactionEditScreen
import ru.finhelper.app.ui.transactions.TransactionFilter
import ru.finhelper.app.ui.transactions.TransactionFormState
import ru.finhelper.app.ui.transactions.TransactionsScreen
import ru.finhelper.app.ui.transactions.accountOptions
import ru.finhelper.app.ui.transactions.buildAccountsState
import ru.finhelper.app.ui.transactions.buildTemplatesState
import ru.finhelper.app.ui.transactions.buildTransactionsState
import ru.finhelper.app.ui.transactions.categoryOptions

/**
 * Снимки экранов приложения для руководства пользователя.
 *
 * Экраны отрисовываются на JVM (Robolectric с настоящей графической подсистемой
 * Android и Roborazzi) без эмулятора. Каждый экран получает состояние, построенное
 * теми же функциями, что и в приложении, по демонстрационному набору данных.
 * Каталог снимков задается свойством screenshots.dir (см. app/build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val directory = File(System.getProperty("screenshots.dir") ?: "build/outputs/screenshots")
    private val snapshot = DemoData.snapshot
    private val today = DemoData.TODAY
    private val userSession = Session("user-1", "anna@example.com", Session.ROLE_USER)
    private val syncedStatus = SyncStatus(online = true, pending = 0, lastSyncAt = DemoData.NOW)

    @Before
    fun setUp() {
        Dates.zone = ZoneOffset.UTC
        directory.mkdirs()
    }

    /** Сохраняет снимок экрана размером с телефон (411×891 dp) либо заданной высоты. */
    private fun capture(
        name: String,
        theme: String = SettingsStore.THEME_LIGHT,
        status: SyncStatus = syncedStatus,
        height: Int = 891,
        content: @Composable () -> Unit,
    ) {
        // Высота экрана задается квалификатором конфигурации: так снимок вмещает длинный экран целиком
        RuntimeEnvironment.setQualifiers("ru-w411dp-h${height}dp-xxhdpi")
        captureRoboImage(File(directory, "$name.png").path) {
            FinHelperTheme(theme) {
                CompositionLocalProvider(LocalSyncStatus provides status) {
                    Box(Modifier.size(411.dp, height.dp)) { content() }
                }
            }
        }
    }

    @Test
    fun onboarding() = capture("00_onboarding") { OnboardingScreen() }

    @Test
    fun login() = capture("01_login") {
        LoginScreen(
            LoginUiState(email = "anna@example.com", password = "password1", serverUrl = "http://192.168.1.10:8000/"),
            serverInitiallyVisible = true,
        )
    }

    @Test
    fun register() = capture("01_register") {
        RegisterScreen(RegisterUiState(displayName = "Анна Смирнова", email = "anna@example.com", password = "password1", passwordRepeat = "password1", consent = true))
    }

    @Test
    fun dashboard() = capture("02_dashboard") { DashboardScreen(buildDashboardState(snapshot, today, userSession, unread = 2)) }

    @Test
    fun dashboardFull() = capture("02_dashboard_full", height = 1640) { DashboardScreen(buildDashboardState(snapshot, today, userSession, unread = 2)) }

    @Test
    fun dashboardDark() = capture("02_dashboard_dark", theme = SettingsStore.THEME_DARK) {
        DashboardScreen(buildDashboardState(snapshot, today, userSession, unread = 2))
    }

    @Test
    fun dashboardOffline() = capture("02_dashboard_offline", status = SyncStatus(online = false, pending = 3, lastSyncAt = DemoData.NOW)) {
        DashboardScreen(buildDashboardState(snapshot, today, userSession))
    }

    @Test
    fun addTransaction() = capture("03_add_transaction") {
        TransactionEditScreen(
            TransactionFormState(
                loading = false, kind = "expense", amount = "1250,50", accountId = DemoData.CARD,
                categoryId = snapshot.categories.first { it.name == "Супермаркеты" }.id, date = today, merchant = "Пятерочка",
                accounts = accountOptions(snapshot.accounts), categories = categoryOptions(snapshot, "expense"),
                templates = DemoData.templates, categorySuggested = true,
            )
        )
    }

    @Test
    fun addTransactionErrors() = capture("03_add_transaction_errors") {
        TransactionEditScreen(
            TransactionFormState(
                loading = false, kind = "expense", accountId = DemoData.CARD, date = today,
                accounts = accountOptions(snapshot.accounts), categories = categoryOptions(snapshot, "expense"),
                amountError = "Введите сумму больше нуля, например 350,00", categoryError = "Выберите категорию из списка",
            )
        )
    }

    @Test
    fun transactions() = capture("04_transactions") { TransactionsScreen(buildTransactionsState(snapshot, TransactionFilter(), today)) }

    @Test
    fun transactionsFilters() = capture("04_transactions_filters") {
        val filter = TransactionFilter(kind = "expense", categoryId = snapshot.categories.first { it.name == "Кафе" }.id)
        TransactionsScreen(buildTransactionsState(snapshot, filter, today), filtersInitiallyExpanded = true)
    }

    @Test
    fun accounts() = capture("04_accounts") { AccountsScreen(buildAccountsState(snapshot)) }

    @Test
    fun recurring() = capture("04_recurring") {
        RecurringScreen(buildTemplatesState(snapshot, DemoData.templates, DemoData.recurring, today))
    }

    @Test
    fun budget() = capture("05_budget") { BudgetScreen(buildBudgetState(snapshot, today)) }

    @Test
    fun goals() = capture("06_goals") { GoalsScreen(buildGoalsState(snapshot, today)) }

    @Test
    fun goalsFull() = capture("06_goals_full", height = 1420) { GoalsScreen(buildGoalsState(snapshot, today)) }

    private val dynamics = (0..30).map { day ->
        val value = 268_000 + day * 1_450 + 5_200 * sin(day / 3.0)
        ValuePointDto(today.minusDays(30L - day).toString(), BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP))
    }

    private fun investmentsState() = InvestmentsUiState(
        loading = false, portfolio = buildPortfolio(snapshot), currency = "RUB", currencies = snapshot.converter.currencies,
        pricesAt = snapshot.pricesAt, dynamicsPeriod = "month", dynamics = dynamics,
    )

    @Test
    fun investments() = capture("07_investments") { InvestmentsScreen(investmentsState(), now = DemoData.NOW) }

    @Test
    fun investmentsFull() = capture("07_investments_full", height = 1720) { InvestmentsScreen(investmentsState(), now = DemoData.NOW) }

    @Test
    fun asset() = capture("08_asset", height = 1180) {
        val history = (0..89).map { day ->
            val price = 262 + day * 0.455 + 6 * sin(day / 5.0) * (89 - day) / 89
            PricePointDto(today.minusDays(89L - day).toString(), BigDecimal(price).setScale(2, java.math.RoundingMode.HALF_UP))
        }
        val details = AssetDto(
            ticker = "SBER", name = "Сбербанк", assetType = "stock", currentPrice = BigDecimal("302.50"), priceAsOf = snapshot.pricesAt,
            history = history, returns = mapOf("week" to 1.42, "month" to 4.85, "quarter" to 11.3, "year" to null),
        )
        AssetScreen(buildAssetState(snapshot, "SBER", DemoData.NOW, details), now = DemoData.NOW)
    }

    @Test
    fun reports() = capture("09_reports") { ReportsScreen(buildReportsState(snapshot, ReportFilter(), today)) }

    @Test
    fun reportsFull() = capture("09_reports_full", height = 1270) { ReportsScreen(buildReportsState(snapshot, ReportFilter(), today)) }

    private val recommendations = listOf(
        RecommendationDto(
            id = "r1", kind = "spending_growth", title = "Расходы на «Кафе» выше обычного",
            text = "В этом месяце на «Кафе» уже потрачено 10 494,00 RUB – на 33 % больше среднемесячного уровня (7 900,00 RUB).",
            explanation = "Сравнение расходов текущего месяца со средним значением за 2 предыдущих мес.",
            estimatedSaving = BigDecimal("2594.00"),
        ),
        RecommendationDto(
            id = "r2", kind = "set_budget", title = "Установите бюджет на «Здоровье»",
            text = "В среднем вы тратите на «Здоровье» 1 843,00 RUB в месяц. Лимит 1 658,70 RUB поможет сократить эти расходы на 10 %.",
            explanation = "Рассчитано по вашим расходам за последние 2 мес.; по этой категории лимит сейчас не установлен.",
            estimatedSaving = BigDecimal("184.30"), feedback = "useful",
            action = JsonObject(mapOf("type" to JsonPrimitive("set_budget"), "label" to JsonPrimitive("Установить бюджет"))),
        ),
        RecommendationDto(
            id = "r3", kind = "goal_pace", title = "Увеличьте накопления на цель «Резервный фонд»",
            text = "Чтобы достичь цели к 31.08.2027, откладывайте 27 000,00 RUB в месяц. Сейчас накоплено 10 % целевой суммы.",
            explanation = "Рассчитано по остатку до целевой суммы и числу месяцев до срока.",
        ),
    )

    @Test
    fun recommendations() = capture("10_recommendations", height = 1280) {
        RecommendationsScreen(
            RecommendationsUiState(
                loading = false, items = recommendations, currency = "RUB",
                forecast = ForecastDto(
                    month = "2026-12", currency = "RUB", confidence = 0.95,
                    expense = ForecastValueDto(forecast = 96_400.0, lower = 88_150.0, upper = 104_650.0),
                    income = ForecastValueDto(forecast = 140_000.0, lower = 133_500.0, upper = 146_500.0),
                ),
                risks = listOf(
                    RiskDto(
                        "risk1", "reserve_fund", "warning",
                        "Резервный фонд покрывает 2,1 мес. расходов при рекомендуемом уровне не менее 3 мес. " +
                            "Рекомендуется откладывать часть дохода на отдельный счет.",
                    )
                ),
            )
        )
    }

    @Test
    fun profile() = capture("11_profile") {
        ProfileScreen(ProfileUiState(DemoData.profile, "anna@example.com", snapshot.converter.currencies))
    }

    @Test
    fun notificationSettings() = capture("11_notification_settings", height = 1100) {
        NotificationSettingsScreen(DemoData.profile.notificationSettings)
    }

    // ------------------------------------------------------------ административный модуль
    private val activeModel = MlModelDto(
        id = "m3", version = 3, status = "ready", isActive = true, samples = 24_500,
        accuracy = 0.874, precision = 0.861, recall = 0.852, f1 = 0.856,
        startedAt = Dates.parseInstant("2026-11-10T09:00:00Z"), trainedAt = Dates.parseInstant("2026-11-10T09:42:00Z"),
    )

    @Test
    fun adminMenu() = capture("12_admin_menu") { AdminMenuScreen(isAdmin = true) }

    @Test
    fun adminAi() = capture("12_admin_ai", height = 1140) {
        AiAnalyticsScreen(
            AiAnalyticsUiState(
                loading = false,
                quality = QualityDto(
                    model = activeModel, minAccuracy = 0.8, forecastMape = 9.6, positiveFeedbackShare = 0.78,
                    ratedRecommendations = 412, appliedRecommendations = 137, trainingSamples = 24_500,
                    history = listOf(
                        QualityPointDto("2026-09", 0.842, 0.83, 0.82, 0.825, 11.4, 0.74),
                        QualityPointDto("2026-10", 0.861, 0.85, 0.84, 0.845, 10.2, 0.76),
                        QualityPointDto("2026-11", 0.874, 0.861, 0.852, 0.856, 9.6, 0.78),
                    ),
                ),
            )
        )
    }

    @Test
    fun adminModels() = capture("13_admin_models", height = 940) {
        ModelsScreen(
            ModelsUiState(
                loading = false,
                models = listOf(
                    // Модель в состоянии «обучается» не показывается: бесконечная анимация индикатора не дает сделать снимок
                    MlModelDto(
                        id = "m4", version = 4, status = "ready", samples = 25_120, accuracy = 0.881, f1 = 0.863,
                        trainedAt = Dates.parseInstant("2026-11-20T10:05:00Z"),
                    ),
                    activeModel,
                    MlModelDto(
                        id = "m2", version = 2, status = "ready", samples = 19_300, accuracy = 0.861, f1 = 0.845,
                        trainedAt = Dates.parseInstant("2026-10-08T10:15:00Z"),
                    ),
                ),
                trainingData = TrainingDataDto(total = 25_120, byCategory = (1..19).associate { "c$it" to 100 }),
            )
        )
    }

    @Test
    fun adminUsers() = capture("14_admin_users") {
        UsersScreen(
            UsersUiState(
                loading = false,
                users = listOf(
                    AdminUserDto("u1", "anna@example.com", "Анна Смирнова", "user", lastLoginAt = Dates.parseInstant("2026-11-20T08:14:00Z")),
                    AdminUserDto("u2", "dmitry@example.com", "Дмитрий Ковалёв", "user", lastLoginAt = Dates.parseInstant("2026-11-19T21:40:00Z")),
                    AdminUserDto("u3", "ekaterina@example.com", "Екатерина Волкова", "analyst", lastLoginAt = Dates.parseInstant("2026-11-20T09:02:00Z")),
                    AdminUserDto("u4", "ivan@example.com", "Иван Петров", "user", isBlocked = true),
                    AdminUserDto("u5", "pavel@example.com", "Павел Смирнов", "admin", lastLoginAt = Dates.parseInstant("2026-11-20T10:30:00Z")),
                ),
            )
        )
    }

    @Test
    fun adminLogs() = capture("15_admin_logs") {
        val rows = listOf(
            Triple("2026-11-20T10:31:12Z", "pavel@example.com", "admin.user_reset_password"),
            Triple("2026-11-20T09:02:41Z", "ekaterina@example.com", "auth.login"),
            Triple("2026-11-20T08:20:05Z", "anna@example.com", "transaction.create"),
            Triple("2026-11-20T08:14:37Z", "anna@example.com", "auth.login"),
            Triple("2026-11-19T21:44:10Z", "dmitry@example.com", "investment_trade.create"),
            Triple("2026-11-19T21:40:02Z", "dmitry@example.com", "auth.login"),
            Triple("2026-11-19T18:05:55Z", "anna@example.com", "budget.update"),
            Triple("2026-11-19T12:11:20Z", "ivan@example.com", "auth.login_failed"),
        )
        LogsScreen(
            LogsUiState(
                loading = false, filter = LogFilter(from = today.minusDays(7), to = today), total = rows.size,
                items = rows.mapIndexed { index, (at, user, action) ->
                    AuditLogDto(id = 100L - index, at = Dates.parseInstant(at), userEmail = user, action = action, ip = "192.168.1.${20 + index}")
                },
            )
        )
    }

    @Test
    fun adminMonitoring() = capture("16_admin_monitoring", height = 1070) {
        MonitoringScreen(
            MonitoringUiState(
                loading = false, hours = 168,
                summary = MonitoringDto(
                    periodHours = 168, availabilityPercent = 99.62, requests = 48_215, serverErrors = 7, loggedErrors = 5,
                    averageResponseMs = 184.3, maxResponseMs = 1_932.0,
                    components = mapOf("database" to true, "ml_service" to true, "rates" to true),
                    lastCheckAt = Dates.parseInstant("2026-11-20T11:59:00Z"),
                    availabilityChart = (0 until 168).map { hour ->
                        AvailabilityPointDto(
                            today.minusDays(6).atStartOfDay().plusHours(hour.toLong()).toString().replace('T', ' ').take(13) + ":00",
                            if (hour == 71) 76.7 else if (hour == 72) 91.7 else 100.0,
                        )
                    },
                ),
                errors = listOf(
                    ErrorLogDto(2, Dates.parseInstant("2026-11-16T23:41:00Z"), "server", "error", "RatesUnavailable", "Источник курсов валют недоступен"),
                    ErrorLogDto(1, Dates.parseInstant("2026-11-16T23:12:00Z"), "client", "error", "sync", "Часть операций синхронизации отклонена"),
                ),
            )
        )
    }

    @Test
    fun adminSettings() = capture("17_admin_settings", height = 1250) {
        fun section(vararg fields: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*fields))
        SystemSettingsScreen(
            SystemSettingsUiState(
                loading = false,
                settings = linkedMapOf(
                    "integrations" to section(
                        "rates_provider" to JsonPrimitive("cbr"), "quotes_provider" to JsonPrimitive("moex"),
                        "push_provider" to JsonPrimitive("fcm"), "email_provider" to JsonPrimitive("smtp"),
                    ),
                    "notifications" to section("budget_warning_percent" to JsonPrimitive(80)),
                    "backup" to section("cron" to JsonPrimitive("0 3 * * *"), "keep_days" to JsonPrimitive(92)),
                    "maintenance" to section("enabled" to JsonPrimitive(false), "message" to JsonPrimitive("")),
                ),
            )
        )
    }
}
