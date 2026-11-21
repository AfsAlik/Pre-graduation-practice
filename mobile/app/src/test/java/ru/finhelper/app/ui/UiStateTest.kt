package ru.finhelper.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.finhelper.app.DemoData
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.prefs.Session
import ru.finhelper.app.data.remote.NotificationSettingsDto
import ru.finhelper.app.data.sync.SyncStatus
import ru.finhelper.app.domain.BudgetLevel
import ru.finhelper.app.notify.LocalAlertRules
import ru.finhelper.app.ui.auth.AuthValidation
import ru.finhelper.app.ui.components.syncStatusText
import ru.finhelper.app.ui.dashboard.buildDashboardState
import ru.finhelper.app.ui.investments.buildAssetState
import ru.finhelper.app.ui.transactions.PeriodPreset
import ru.finhelper.app.ui.transactions.TransactionFilter
import ru.finhelper.app.ui.transactions.buildTransactionsState

/** Проверка построения состояния экранов, локальных уведомлений и индикатора синхронизации. */
@RunWith(RobolectricTestRunner::class)
class UiStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val snapshot = DemoData.snapshot
    private val today = DemoData.TODAY

    @Before
    fun setUp() {
        Dates.zone = ZoneOffset.UTC
    }

    /** Главный экран собирает показатели всех подсистем. */
    @Test
    fun dashboardCollectsIndicatorsOfAllSubsystems() {
        val state = buildDashboardState(snapshot, today, Session("u", "anna@example.com", Session.ROLE_ANALYST), unread = 3)
        assertEquals(BigDecimal("140000.00"), state.income)
        assertEquals(BigDecimal("85748.00"), state.expense)
        assertEquals(20, state.dailyExpenses.size)
        assertEquals("Жилье", state.categories.first().name)
        assertEquals(2, state.budget!!.exceeded)
        assertEquals(BudgetLevel.OK, state.budget!!.level)
        // Показывается цель с наивысшим приоритетом
        assertEquals("Отпуск в Сочи", state.goal!!.goal.name)
        assertEquals(5, state.portfolio!!.positions)
        assertEquals(10, state.forecast!!.daysLeft)
        assertTrue(state.showAdminMenu)
        assertEquals(3, state.unreadNotifications)
        assertFalse(buildDashboardState(snapshot, today, Session("u", "a@b.ru", Session.ROLE_USER)).showAdminMenu)
    }

    /** История операций фильтруется по периоду категории счету сумме и строке поиска. */
    @Test
    fun transactionsAreFilteredByPeriodCategoryAccountAmountAndQuery() {
        fun count(filter: TransactionFilter) = buildTransactionsState(snapshot, filter, today).count
        assertEquals(21, count(TransactionFilter()))
        assertEquals(snapshot.transactions.size, count(TransactionFilter(period = PeriodPreset.ALL)))
        assertEquals(25, count(TransactionFilter(period = PeriodPreset.LAST_MONTH)))
        assertEquals(5, count(TransactionFilter(categoryId = SystemCategories.id("Кафе"))))
        assertEquals(2, count(TransactionFilter(kind = "income")))
        assertEquals(2, count(TransactionFilter(accountId = DemoData.CASH)))
        assertEquals(3, count(TransactionFilter(amountMin = "30000")))
        assertEquals(2, count(TransactionFilter(query = "пятерочка")))
        assertEquals(1, count(TransactionFilter(period = PeriodPreset.CUSTOM, from = LocalDate.of(2026, 10, 25), to = LocalDate.of(2026, 10, 25))))
        val state = buildTransactionsState(snapshot, TransactionFilter(), today)
        assertEquals(LocalDate.of(2026, 11, 20), state.groups.first().date)
        assertEquals("+45 000,00 ₽", state.groups.first().rows.single().amount)
    }

    /** Карточка актива показывает сделки и запрет изменения старых. */
    @Test
    fun assetCardShowsTradesAndLocksOldOnes() {
        val state = buildAssetState(snapshot, "SBER", DemoData.NOW)
        assertEquals(2, state.trades.size)
        assertTrue(state.trades.first { it.trade.id == "tr1" }.locked)
        assertFalse(state.trades.first { it.trade.id == "tr2" }.locked)
        assertEquals(0, BigDecimal("150").compareTo(state.position!!.state.quantity))
        assertNull(buildAssetState(snapshot, "LKOH", DemoData.NOW).position)
    }

    /** Локальные уведомления о бюджете и платежах. */
    @Test
    fun localAlertsForBudgetsAndPayments() {
        val alerts = LocalAlertRules.compute(snapshot, DemoData.recurring, today)
        val titles = alerts.map { it.title }
        assertTrue("Превышен бюджет: Кафе" in titles)
        assertTrue("Превышен бюджет: Развлечения" in titles)
        assertTrue("Бюджет почти исчерпан: Продукты" in titles)
        assertFalse(titles.any { it.contains("Транспорт") })
        // Платеж с датой сегодня; до аренды 11 дней при напоминании за 3 дня; цель достигнута давно
        assertEquals(listOf("Предстоящий платеж: Интернет Дом.ру"), titles.filter { it.startsWith("Предстоящий") })
        assertTrue(alerts.first { it.kind == LocalAlertRules.KIND_RECURRING }.body.contains("сегодня"))
        assertFalse(alerts.any { it.kind == LocalAlertRules.KIND_GOAL_ACHIEVED })
        assertEquals(alerts.size, alerts.map { it.key }.toSet().size)
        assertTrue(alerts.first { it.title.endsWith("Кафе") }.key.endsWith("month:2026-11-01"))
    }

    /** Локальные уведомления учитывают настройки пользователя. */
    @Test
    fun localAlertsRespectUserSettings() {
        val muted = snapshot.copy(profile = snapshot.profile.copy(notificationSettings = NotificationSettingsDto(budget = false, recurring = false)))
        assertTrue(LocalAlertRules.compute(muted, DemoData.recurring, today).isEmpty())
        val strict = snapshot.copy(profile = snapshot.profile.copy(notificationSettings = NotificationSettingsDto(budgetThresholdPercent = 50)))
        assertTrue(LocalAlertRules.compute(strict, emptyList(), today).any { it.title == "Бюджет почти исчерпан: Транспорт" })
        // Напоминание появляется за заданное число дней до платежа
        val rentSoon = LocalAlertRules.compute(snapshot, DemoData.recurring, LocalDate.of(2026, 11, 28))
        assertTrue(rentSoon.any { it.title == "Предстоящий платеж: Аренда квартиры" && it.body.contains("через 3 дн.") })
    }

    /** Проверка адреса электронной почты и пароля. */
    @Test
    fun emailAndPasswordValidation() {
        assertNull(AuthValidation.emailError(" anna@example.com "))
        assertEquals(AuthValidation.EMAIL_ERROR, AuthValidation.emailError("anna@example"))
        assertNull(AuthValidation.passwordError("password1"))
        assertEquals(AuthValidation.PASSWORD_ERROR, AuthValidation.passwordError("short1"))
        assertEquals(AuthValidation.PASSWORD_ERROR, AuthValidation.passwordError("12345678"))
        assertEquals(AuthValidation.PASSWORD_ERROR, AuthValidation.passwordError("onlyletters"))
    }

    /** Индикатор синхронизации показывает режим и число ожидающих операций. */
    @Test
    fun syncIndicatorShowsModeAndPendingCount() {
        val cases = listOf(
            SyncStatus(online = true) to "Онлайн · синхронизировано",
            SyncStatus(online = true, syncing = true) to "Онлайн · синхронизация…",
            SyncStatus(online = true, pending = 1) to "Онлайн · ожидает 1 операция",
            SyncStatus(online = false, pending = 3) to "Офлайн · ожидает 3 операции",
            SyncStatus(online = false, pending = 25) to "Офлайн · ожидает 25 операций",
            SyncStatus(online = true, pending = 11) to "Онлайн · ожидает 11 операций",
            SyncStatus(online = true, pending = 21) to "Онлайн · ожидает 21 операция",
            SyncStatus(online = false) to "Офлайн · данные сохранены на устройстве",
            SyncStatus(online = true, maintenance = true, pending = 2) to "Технические работы · ожидает 2 операции",
            SyncStatus(online = true, error = "Ошибка") to "Онлайн · ошибка синхронизации",
        )
        composeRule.setContent { cases.forEach { (status, _) -> Text(syncStatusText(status)) } }
        cases.forEach { (_, expected) -> composeRule.onNodeWithText(expected).assertExists() }
    }
}
