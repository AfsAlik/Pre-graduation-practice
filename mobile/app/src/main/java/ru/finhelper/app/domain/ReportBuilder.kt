package ru.finhelper.app.domain

import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.CategoryEntity
import ru.finhelper.app.data.local.TransactionEntity
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Сумма по категории верхнего уровня и ее доля в общей сумме. */
data class CategoryAmount(val categoryId: String?, val name: String, val color: String, val amount: BigDecimal, val share: Double)

/** Доходы и расходы за один день или месяц. */
data class PeriodAmount(val label: String, val income: BigDecimal, val expense: BigDecimal)

/** Прогноз остатка средств на конец текущего месяца (п. 4.2.1.4 г ТЗ). */
data class BalanceForecast(
    val currentBalance: BigDecimal,
    val expectedIncome: BigDecimal,
    val expectedExpense: BigDecimal,
    val forecastBalance: BigDecimal,
    val daysLeft: Int,
)

/** Аналитический отчет за период (п. 4.2.1.4 б ТЗ). */
data class Report(
    val start: LocalDate,
    val end: LocalDate,
    val income: BigDecimal,
    val expense: BigDecimal,
    val operations: Int,
    val expensesByCategory: List<CategoryAmount>,
    val incomeByCategory: List<CategoryAmount>,
    /** Динамика доходов и расходов: по дням для периода до 31 дня, иначе по месяцам. */
    val dynamics: List<PeriodAmount>,
    val summary: String,
)

/**
 * Формирование аналитики по данным локальной базы: показатели главного экрана и
 * отчеты доступны без подключения к сети. Правила расчета совпадают с серверными
 * (services/reports.py).
 */
class ReportBuilder(
    private val categories: Map<String, CategoryEntity>,
    private val converter: CurrencyConverter,
    private val baseCurrency: String,
) {
    private fun inBase(item: TransactionEntity): BigDecimal = converter.convert(item.amount, item.currency, baseCurrency)

    /** Операции периода с учетом фильтров по счетам и категориям (п. 4.2.1.4 в ТЗ). */
    fun select(
        transactions: List<TransactionEntity>,
        start: LocalDate,
        end: LocalDate,
        accountIds: Set<String> = emptySet(),
        categoryIds: Set<String> = emptySet(),
    ): List<TransactionEntity> {
        val from = Dates.startOfDay(start)
        val to = Dates.endOfDay(end)
        val categoryFilter = categoryIds.flatMap { CategoryTree.subtreeIds(categories, it) }.toSet()
        return transactions.filter { item ->
            item.deletedAt == null && item.occurredAt in from..to &&
                (accountIds.isEmpty() || item.accountId in accountIds) &&
                (categoryFilter.isEmpty() || item.categoryId in categoryFilter)
        }
    }

    /** Суммы доходов и расходов в базовой валюте; переводы между счетами не учитываются. */
    fun totals(items: List<TransactionEntity>): Pair<BigDecimal, BigDecimal> {
        var income = BigDecimal.ZERO
        var expense = BigDecimal.ZERO
        for (item in items) {
            when (item.kind) {
                BalanceCalculator.INCOME -> income += inBase(item)
                BalanceCalculator.EXPENSE -> expense += inBase(item)
            }
        }
        return income to expense
    }

    /** Суммы по категориям верхнего уровня с долями, по убыванию суммы. */
    fun byCategory(items: List<TransactionEntity>, kind: String = BalanceCalculator.EXPENSE): List<CategoryAmount> {
        val sums = linkedMapOf<String?, BigDecimal>()
        for (item in items) {
            if (item.kind != kind) continue
            sums.merge(CategoryTree.rootOf(categories, item.categoryId)?.id, inBase(item), BigDecimal::add)
        }
        val total = sums.values.fold(BigDecimal.ZERO, BigDecimal::add)
        return sums.map { (id, value) ->
            val root = id?.let(categories::get)
            CategoryAmount(
                categoryId = id,
                name = root?.name ?: "Без категории",
                color = root?.color?.ifBlank { null } ?: "#9E9E9E",
                amount = value.setScale(2, RoundingMode.HALF_UP),
                share = if (total.signum() > 0) {
                    value.multiply(BigDecimal(100)).divide(total, MathContext.DECIMAL64).setScale(1, RoundingMode.HALF_UP).toDouble()
                } else {
                    0.0
                },
            )
        }.sortedByDescending { it.amount }
    }

    /** Доходы и расходы по дням периода. */
    fun daily(items: List<TransactionEntity>, start: LocalDate, end: LocalDate): List<PeriodAmount> {
        val byDay = items.groupBy { Dates.toLocalDate(it.occurredAt) }
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.map { day ->
            val (income, expense) = totals(byDay[day].orEmpty())
            PeriodAmount(Dates.formatShort(day), income, expense)
        }.toList()
    }

    /** Доходы и расходы по месяцам за [months] месяцев, включая месяц даты [end]. */
    fun monthly(transactions: List<TransactionEntity>, end: LocalDate, months: Int = 12): List<PeriodAmount> {
        val byMonth = transactions.filter { it.deletedAt == null }.groupBy { YearMonth.from(Dates.toLocalDate(it.occurredAt)) }
        val first = YearMonth.from(end).minusMonths(months - 1L)
        return (0 until months).map { index ->
            val month = first.plusMonths(index.toLong())
            val (income, expense) = totals(byMonth[month].orEmpty())
            PeriodAmount(Dates.monthShort(month), income, expense)
        }
    }

    /**
     * Прогноз остатка на конец месяца: текущий баланс ликвидных счетов плюс ожидаемые
     * доходы и минус ожидаемые расходы за оставшиеся дни, оцененные по среднедневным
     * оборотам за предыдущие 90 дней.
     */
    fun balanceForecast(accounts: List<AccountEntity>, transactions: List<TransactionEntity>, today: LocalDate): BalanceForecast {
        val daysLeft = today.lengthOfMonth() - today.dayOfMonth
        val history = select(transactions, today.minusDays(HISTORY_DAYS), today.minusDays(1))
        val (income, expense) = totals(history)
        val span = history.minOfOrNull { it.occurredAt }
            ?.let { maxOf(ChronoUnit.DAYS.between(Dates.toLocalDate(it), today), 1) } ?: HISTORY_DAYS
        val balances = BalanceCalculator.accountBalances(accounts, transactions)
        val balance = BalanceCalculator.totalBalance(accounts, balances, converter, baseCurrency, liquidOnly = true)
        val perDay = { value: BigDecimal -> value.multiply(BigDecimal(daysLeft)).divide(BigDecimal(span), 2, RoundingMode.HALF_UP) }
        val expectedIncome = perDay(income)
        val expectedExpense = perDay(expense)
        return BalanceForecast(
            currentBalance = balance.setScale(2, RoundingMode.HALF_UP),
            expectedIncome = expectedIncome,
            expectedExpense = expectedExpense,
            forecastBalance = (balance + expectedIncome - expectedExpense).setScale(2, RoundingMode.HALF_UP),
            daysLeft = daysLeft,
        )
    }

    /** Текстовая сводка отчета. */
    fun summary(income: BigDecimal, expense: BigDecimal, expenses: List<CategoryAmount>): String {
        val parts = mutableListOf(
            "Доходы за период составили ${Money.format(income, baseCurrency)}, расходы – ${Money.format(expense, baseCurrency)}."
        )
        val balance = income - expense
        parts += if (balance.signum() >= 0) {
            "Доходы превысили расходы на ${Money.format(balance, baseCurrency)}."
        } else {
            "Расходы превысили доходы на ${Money.format(balance.negate(), baseCurrency)}."
        }
        expenses.firstOrNull()?.let { top ->
            parts += "Наибольшая статья расходов – «${top.name}»: ${Money.format(top.amount, baseCurrency)} " +
                "(${Money.formatPercent(top.share)} всех расходов)."
        }
        return parts.joinToString(" ")
    }

    /** Строит отчет за период с фильтрами. */
    fun report(
        transactions: List<TransactionEntity>,
        start: LocalDate,
        end: LocalDate,
        accountIds: Set<String> = emptySet(),
        categoryIds: Set<String> = emptySet(),
    ): Report {
        val items = select(transactions, start, end, accountIds, categoryIds)
        val (income, expense) = totals(items)
        val expenses = byCategory(items, BalanceCalculator.EXPENSE)
        val days = ChronoUnit.DAYS.between(start, end) + 1
        return Report(
            start = start,
            end = end,
            income = income.setScale(2, RoundingMode.HALF_UP),
            expense = expense.setScale(2, RoundingMode.HALF_UP),
            operations = items.size,
            expensesByCategory = expenses,
            incomeByCategory = byCategory(items, BalanceCalculator.INCOME),
            dynamics = if (days <= MAX_DAILY_POINTS) {
                daily(items, start, end)
            } else {
                monthly(items, end, months = (ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) + 1).toInt())
            },
            summary = summary(income, expense, expenses),
        )
    }

    private companion object {
        const val HISTORY_DAYS = 90L
        const val MAX_DAILY_POINTS = 31L
    }
}
