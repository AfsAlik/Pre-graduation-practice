package ru.finhelper.app.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.finhelper.app.DemoData
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.BudgetEntity
import ru.finhelper.app.data.local.GoalContributionEntity
import ru.finhelper.app.data.local.GoalEntity
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.local.TransactionEntity

/** Проверка расчета исполнения бюджета и прогресса целей (п. 4.2.1.2 ТЗ). */
class BudgetAndGoalTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val categories = SystemCategories.all().associateBy { it.id }
    private val cafe = SystemCategories.id("Кафе")
    private val coffee = SystemCategories.id("Кафе/Кофейни")
    private val budget = BudgetEntity("b1", cafe, "month", BigDecimal("8000.00"))

    @Before
    fun setUp() {
        Dates.zone = ZoneOffset.UTC
    }

    private fun expense(id: String, date: String, amount: String, category: String = cafe, currency: String = "RUB", deleted: Boolean = false) =
        TransactionEntity(
            id = id, kind = "expense", amount = BigDecimal(amount), currency = currency, accountId = "a", categoryId = category,
            occurredAt = Dates.noon(LocalDate.parse(date)), deletedAt = if (deleted) 1L else null,
        )

    private fun status(vararg items: TransactionEntity, threshold: Int = 80) =
        BudgetCalculator.status(budget, categories, items.toList(), today, CurrencyConverter.FALLBACK, threshold)

    // ------------------------------------------------------------ периоды
    /** Границы недели месяца и года. */
    @Test
    fun weekMonthAndYearBounds() {
        assertEquals(LocalDate.of(2026, 11, 16) to LocalDate.of(2026, 11, 22), Periods.bounds("week", today))
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 11, 30), Periods.bounds("month", today))
        assertEquals(LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 12, 31), Periods.bounds("year", today))
        assertEquals("month:2026-11-01", Periods.key("month", today))
    }

    /** Число месяцев до срока считается с неполным месяцем. */
    @Test
    fun monthsUntilDeadlineIncludePartialMonth() {
        assertEquals(7, Periods.monthsBetween(today, LocalDate.of(2027, 6, 1)))
        assertEquals(10, Periods.monthsBetween(today, LocalDate.of(2027, 8, 31)))
        assertEquals(1, Periods.monthsBetween(today, LocalDate.of(2026, 11, 25)))
        assertEquals(1, Periods.monthsBetween(today, today))
    }

    // ------------------------------------------------------------ бюджет
    /** Пороги статусов бюджета совпадают с серверными. */
    @Test
    fun budgetThresholdsMatchServer() {
        assertEquals(BudgetLevel.OK, BudgetCalculator.classify(100.0))
        assertEquals(BudgetLevel.EXCEEDED, BudgetCalculator.classify(100.01))
        assertEquals(BudgetLevel.EXCEEDED, BudgetCalculator.classify(120.0))
        assertEquals(BudgetLevel.CRITICAL, BudgetCalculator.classify(120.01))
    }

    /** Расходы подкатегорий входят в лимит категории а предупреждение формируется на 80 процентах. */
    @Test
    fun subcategoryExpensesCountAndWarningAt80Percent() {
        val result = status(expense("1", "2026-11-05", "3000.00", coffee), expense("2", "2026-11-10", "3400.00"))
        assertEquals(0, BigDecimal("6400.00").compareTo(result.spent))
        assertEquals(0, BigDecimal("1600.00").compareTo(result.remaining))
        assertEquals(80.0, result.percent, 0.0)
        assertEquals(BudgetLevel.OK, result.level)
        assertTrue(result.warning)
        assertEquals("Кафе", result.categoryName)
    }

    /** Порог предупреждения берется из настроек пользователя. */
    @Test
    fun warningThresholdComesFromUserSettings() {
        assertFalse(status(expense("1", "2026-11-05", "6400.00"), threshold = 90).warning)
        assertTrue(status(expense("1", "2026-11-05", "7200.00"), threshold = 90).warning)
    }

    /** Превышение и критическое превышение. */
    @Test
    fun exceededAndCriticalLevels() {
        val exceeded = status(expense("1", "2026-11-05", "8100.00"))
        assertEquals(BudgetLevel.EXCEEDED, exceeded.level)
        assertEquals(101.3, exceeded.percent, 0.0)
        assertEquals(0, BigDecimal("-100.00").compareTo(exceeded.remaining))
        val critical = status(expense("1", "2026-11-05", "9700.00"))
        assertEquals(BudgetLevel.CRITICAL, critical.level)
        assertEquals(121.3, critical.percent, 0.0)
    }

    /** В лимит не входят операции вне периода удаленные и других категорий. */
    @Test
    fun budgetIgnoresOtherPeriodsDeletedAndOtherCategories() {
        val result = status(
            expense("1", "2026-10-31", "5000.00"),
            expense("2", "2026-11-03", "5000.00", deleted = true),
            expense("3", "2026-11-04", "5000.00", SystemCategories.id("Продукты")),
            expense("4", "2026-11-05", "1000.00").copy(kind = "income"),
            expense("5", "2026-11-06", "10.00", currency = "USD"),
        )
        // 10 USD по курсу 90 = 900 RUB
        assertEquals(0, BigDecimal("900.00").compareTo(result.spent))
    }

    /** Статусы бюджета демонстрационного набора. */
    @Test
    fun budgetStatusesOfDemoData() {
        val statuses = BudgetCalculator.overview(
            DemoData.budgets, categories, DemoData.transactions, DemoData.TODAY, DemoData.converter,
        ).associateBy { it.categoryName }
        assertEquals(BudgetLevel.OK, statuses.getValue("Продукты").level)
        assertTrue(statuses.getValue("Продукты").warning)
        assertEquals(86.8, statuses.getValue("Продукты").percent, 0.0)
        assertEquals(BudgetLevel.CRITICAL, statuses.getValue("Кафе").level)
        assertEquals(BudgetLevel.EXCEEDED, statuses.getValue("Развлечения").level)
        assertEquals(BudgetLevel.OK, statuses.getValue("Транспорт").level)
        assertFalse(statuses.getValue("Транспорт").warning)
    }

    // ------------------------------------------------------------ цели
    private fun progress(id: String) = GoalCalculator.progress(DemoData.goals.first { it.id == id }, DemoData.contributions, today)

    /** Прогресс прогноз и рекомендуемая сумма цели. */
    @Test
    fun goalProgressForecastAndRecommendedAmount() {
        val vacation = progress("goal-vacation")
        assertEquals(0, BigDecimal("48000.00").compareTo(vacation.saved))
        assertEquals(0, BigDecimal("72000.00").compareTo(vacation.remaining))
        assertEquals(40.0, vacation.percent, 0.0)
        // 72 000 / 7 месяцев = 10 285,714… с округлением вверх до копейки
        assertEquals(BigDecimal("10285.72"), vacation.recommendedMonthly)
        // 48 000 накоплено за 118 дней, оставшиеся 72 000 – еще за 177 дней
        assertEquals(LocalDate.of(2027, 5, 16), vacation.forecastDate)
        assertTrue(vacation.onTrack)
        assertFalse(vacation.readOnly)
    }

    /** Цель отстает от срока если прогноз позже срока. */
    @Test
    fun goalIsBehindWhenForecastIsAfterDeadline() {
        val reserve = progress("goal-reserve")
        assertEquals(10.0, reserve.percent, 0.0)
        assertEquals(LocalDate.of(2029, 1, 11), reserve.forecastDate)
        assertFalse(reserve.onTrack)
        assertEquals(BigDecimal("27000.00"), reserve.recommendedMonthly)
    }

    /** Достигнутая цель доступна только для просмотра. */
    @Test
    fun achievedGoalIsReadOnly() {
        val bike = progress("goal-bike")
        assertTrue(bike.readOnly)
        assertEquals(100.0, bike.percent, 0.0)
        assertEquals(0, bike.remaining.signum())
        assertEquals(0, bike.recommendedMonthly.signum())
        assertEquals(LocalDate.of(2026, 9, 12), bike.forecastDate)
        assertTrue(bike.onTrack)
    }

    /** Цель считается достигнутой по сумме накоплений до подтверждения сервером. */
    @Test
    fun goalIsAchievedLocallyBeforeServerConfirms() {
        val goal = GoalEntity("g", "Телефон", BigDecimal("1000.00"), "RUB", "2027-01-01")
        val contributions = listOf(GoalContributionEntity("c", "g", BigDecimal("1200.00"), Dates.noon(today)))
        val result = GoalCalculator.progress(goal, contributions, today)
        assertTrue(result.readOnly)
        assertEquals(100.0, result.percent, 0.0)
        assertEquals(today, result.forecastDate)
    }

    /** Без накоплений прогноза нет а темп оценивается не менее чем за 30 дней. */
    @Test
    fun noForecastWithoutContributionsAndPaceOverAtLeast30Days() {
        val goal = GoalEntity("g", "Телефон", BigDecimal("10000.00"), "RUB", "2027-11-20", createdAt = Dates.noon(today.minusDays(5)))
        val empty = GoalCalculator.progress(goal, emptyList(), today)
        assertNull(empty.forecastDate)
        assertFalse(empty.onTrack)
        assertEquals(BigDecimal("833.34"), empty.recommendedMonthly)
        val recent = listOf(GoalContributionEntity("c", "g", BigDecimal("1000.00"), Dates.noon(today.minusDays(5))))
        // 1 000 за 30 дней (а не за 5) – оставшиеся 9 000 за 270 дней
        assertEquals(today.plusDays(270), GoalCalculator.progress(goal, recent, today).forecastDate)
    }
}
