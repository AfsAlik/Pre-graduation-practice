package ru.finhelper.app.domain

import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.BudgetEntity
import ru.finhelper.app.data.local.CategoryEntity
import ru.finhelper.app.data.local.TransactionEntity
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** Статус исполнения бюджета (п. 4.2.1.2 б ТЗ). */
enum class BudgetLevel(val title: String) {
    OK("в пределах лимита"),
    EXCEEDED("превышение"),
    CRITICAL("критическое превышение"),
}

/** Исполнение одного бюджетного лимита за текущий период. */
data class BudgetStatus(
    val budget: BudgetEntity,
    val categoryName: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val spent: BigDecimal,
    val remaining: BigDecimal,
    /** Процент исполнения с точностью до десятых. */
    val percent: Double,
    val level: BudgetLevel,
    /** Достигнут порог предупреждения (по умолчанию 80 % лимита). */
    val warning: Boolean,
)

/**
 * Контроль исполнения бюджета. Пороговые значения совпадают с серверной частью
 * (services/budget.py): более 100 % – превышение, более 120 % – критическое превышение.
 */
object BudgetCalculator {
    const val CRITICAL_PERCENT = 120.0
    const val DEFAULT_WARNING_PERCENT = 80

    fun classify(percent: Double): BudgetLevel = when {
        percent > CRITICAL_PERCENT -> BudgetLevel.CRITICAL
        percent > 100.0 -> BudgetLevel.EXCEEDED
        else -> BudgetLevel.OK
    }

    /** Расходы по категории и ее подкатегориям за период, пересчитанные в валюту лимита. */
    fun spent(
        categoryIds: Set<String>,
        transactions: List<TransactionEntity>,
        start: LocalDate,
        end: LocalDate,
        currency: String,
        converter: CurrencyConverter,
    ): BigDecimal {
        val from = Dates.startOfDay(start)
        val to = Dates.endOfDay(end)
        return transactions
            .filter { it.deletedAt == null && it.kind == BalanceCalculator.EXPENSE && it.categoryId in categoryIds && it.occurredAt in from..to }
            .fold(BigDecimal.ZERO) { total, item -> total + converter.convert(item.amount, item.currency, currency) }
    }

    fun status(
        budget: BudgetEntity,
        categories: Map<String, CategoryEntity>,
        transactions: List<TransactionEntity>,
        today: LocalDate,
        converter: CurrencyConverter,
        warningPercent: Int = DEFAULT_WARNING_PERCENT,
    ): BudgetStatus {
        val (start, end) = Periods.bounds(budget.period, today)
        val spent = spent(CategoryTree.subtreeIds(categories, budget.categoryId), transactions, start, end, budget.currency, converter)
        val limit = budget.limitAmount
        val percent = if (limit.signum() > 0) {
            spent.multiply(BigDecimal(100)).divide(limit, MathContext.DECIMAL64).toDouble()
        } else {
            0.0
        }
        return BudgetStatus(
            budget = budget,
            categoryName = CategoryTree.displayName(categories, budget.categoryId),
            periodStart = start,
            periodEnd = end,
            spent = spent,
            remaining = limit - spent,
            percent = BigDecimal.valueOf(percent).setScale(1, RoundingMode.HALF_UP).toDouble(),
            level = classify(percent),
            warning = percent >= warningPercent,
        )
    }

    /** Статусы всех действующих лимитов. */
    fun overview(
        budgets: List<BudgetEntity>,
        categories: Map<String, CategoryEntity>,
        transactions: List<TransactionEntity>,
        today: LocalDate,
        converter: CurrencyConverter,
        warningPercent: Int = DEFAULT_WARNING_PERCENT,
    ): List<BudgetStatus> = budgets.filter { it.deletedAt == null && it.isActive }
        .map { status(it, categories, transactions, today, converter, warningPercent) }
}
