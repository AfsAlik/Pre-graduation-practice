package ru.finhelper.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.finhelper.app.DemoData
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.local.TransactionEntity
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

/** Проверка балансов, отчетов и прогноза остатка (п. 4.2.1.1 е, 4.2.1.4 ТЗ). */
class ReportBuilderTest {
    private val today = DemoData.TODAY
    private val snapshot = DemoData.snapshot
    private val builder = ReportBuilder(snapshot.categoryMap, snapshot.converter, "RUB")

    @Before
    fun setUp() {
        Dates.zone = ZoneOffset.UTC
    }

    private fun tx(id: String, kind: String, amount: String, date: LocalDate, account: String = "a", to: String? = null, amountTo: String? = null) =
        TransactionEntity(
            id = id, kind = kind, amount = BigDecimal(amount), accountId = account, toAccountId = to,
            amountTo = amountTo?.let(::BigDecimal), occurredAt = Dates.noon(date),
        )

    /** Баланс счета учитывает доходы расходы и переводы. */
    @Test
    fun accountBalanceCountsIncomeExpenseAndTransfers() {
        val accounts = listOf(
            AccountEntity("a", "Карта", "bank", "RUB", BigDecimal("1000.00")),
            AccountEntity("b", "Доллары", "bank", "USD", BigDecimal("10.00")),
            AccountEntity("c", "Кредитная карта", "credit", "RUB", BigDecimal("-500.00")),
        )
        val items = listOf(
            tx("1", "income", "5000.00", today),
            tx("2", "expense", "1200.00", today),
            tx("3", "transfer", "900.00", today, to = "b", amountTo = "10.00"),
            tx("4", "expense", "300.00", today).copy(deletedAt = 1),
        )
        val balances = BalanceCalculator.accountBalances(accounts, items)
        assertEquals(BigDecimal("3900.00"), balances["a"])
        assertEquals(BigDecimal("20.00"), balances["b"])
        assertEquals(BigDecimal("-500.00"), balances["c"])
        // 3900 + 20 USD × 90 − 500 = 5200; ликвидные средства – без кредитного счета
        assertEquals(BigDecimal("5200.00"), BalanceCalculator.totalBalance(accounts, balances, CurrencyConverter.FALLBACK, "RUB"))
        assertEquals(BigDecimal("5700.00"), BalanceCalculator.totalBalance(accounts, balances, CurrencyConverter.FALLBACK, "RUB", liquidOnly = true))
    }

    /** Итоги месяца и расходы по категориям верхнего уровня. */
    @Test
    fun monthTotalsAndTopLevelCategories() {
        val report = builder.report(snapshot.transactions, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        assertEquals(BigDecimal("140000.00"), report.income)
        assertEquals(BigDecimal("85748.00"), report.expense)
        assertEquals(21, report.operations)
        val top = report.expensesByCategory.first()
        assertEquals("Жилье", top.name)
        assertEquals(BigDecimal("41200.00"), top.amount)
        assertEquals(48.0, top.share, 0.0)
        assertEquals(100.0, report.expensesByCategory.sumOf { it.share }, 0.2)
        assertEquals(30, report.dynamics.size)
        assertTrue(report.summary.contains("Доходы превысили расходы на"))
        assertTrue(report.summary.contains("«Жилье»"))
    }

    /** Фильтр по категории включает подкатегории а по счету – только его операции. */
    @Test
    fun categoryFilterIncludesSubcategories() {
        val cafe = SystemCategories.id("Кафе")
        val items = builder.select(snapshot.transactions, LocalDate.of(2026, 11, 1), today, categoryIds = setOf(cafe))
        assertEquals(5, items.size)
        assertTrue(items.all { CategoryTree.rootOf(snapshot.categoryMap, it.categoryId)?.id == cafe })
        val cash = builder.select(snapshot.transactions, LocalDate.of(2026, 11, 1), today, accountIds = setOf(DemoData.CASH))
        assertTrue(cash.isNotEmpty() && cash.all { it.accountId == DemoData.CASH })
    }

    /** Для длинного периода динамика строится по месяцам. */
    @Test
    fun longPeriodDynamicsIsMonthly() {
        val report = builder.report(snapshot.transactions, LocalDate.of(2026, 9, 1), today)
        assertEquals(listOf("сен", "окт", "ноя"), report.dynamics.map { it.label })
        assertEquals(BigDecimal("140000.00"), report.dynamics.first().income.setScale(2))
    }

    /** Прогноз остатка на конец месяца. */
    @Test
    fun monthEndBalanceForecast() {
        val accounts = listOf(AccountEntity("a", "Наличные", "cash", "RUB", BigDecimal("10000.00")))
        val items = listOf(tx("1", "income", "9000.00", today.minusDays(30)), tx("2", "expense", "3000.00", today.minusDays(10)))
        val forecast = ReportBuilder(emptyMap(), CurrencyConverter.FALLBACK, "RUB").balanceForecast(accounts, items, today)
        // За 30 дней истории: доход 300 в день, расход 100 в день; до конца ноября 10 дней
        assertEquals(10, forecast.daysLeft)
        assertEquals(BigDecimal("16000.00"), forecast.currentBalance)
        assertEquals(BigDecimal("3000.00"), forecast.expectedIncome)
        assertEquals(BigDecimal("1000.00"), forecast.expectedExpense)
        assertEquals(BigDecimal("18000.00"), forecast.forecastBalance)
    }

    /** Сводка сообщает о превышении расходов. */
    @Test
    fun summaryReportsExpenseExcess() {
        val text = builder.summary(BigDecimal("100.00"), BigDecimal("250.00"), emptyList())
        assertTrue(text.contains("Расходы превысили доходы на 150,00"))
    }
}
