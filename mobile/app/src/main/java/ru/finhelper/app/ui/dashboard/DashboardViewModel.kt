package ru.finhelper.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.NotificationDao
import ru.finhelper.app.data.prefs.Session
import ru.finhelper.app.data.prefs.SessionStore
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.FinanceSnapshot
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.domain.BalanceForecast
import ru.finhelper.app.domain.BudgetCalculator
import ru.finhelper.app.domain.BudgetLevel
import ru.finhelper.app.domain.CategoryAmount
import ru.finhelper.app.domain.GoalCalculator
import ru.finhelper.app.domain.GoalProgress
import ru.finhelper.app.domain.PortfolioCalculator
import ru.finhelper.app.domain.ReportBuilder

/** Сводный индикатор исполнения бюджета по всем лимитам. */
data class BudgetSummary(val limit: BigDecimal, val spent: BigDecimal, val percent: Double, val level: BudgetLevel, val exceeded: Int)

/** Сводка по инвестиционному портфелю. */
data class PortfolioSummary(val value: BigDecimal, val profit: BigDecimal, val profitPercent: Double, val positions: Int)

/** Состояние главного экрана (дашборда, п. 4.2.1.4 а ТЗ). */
data class DashboardUiState(
    val loading: Boolean = true,
    val currency: String = "RUB",
    val month: YearMonth = YearMonth.now(),
    val totalBalance: BigDecimal = BigDecimal.ZERO,
    val income: BigDecimal = BigDecimal.ZERO,
    val expense: BigDecimal = BigDecimal.ZERO,
    /** Расходы по дням текущего месяца для графика. */
    val dailyExpenses: List<Double> = emptyList(),
    val dailyLabels: List<String> = emptyList(),
    val categories: List<CategoryAmount> = emptyList(),
    /** Значки категорий по идентификатору категории верхнего уровня. */
    val categoryIcons: Map<String?, String> = emptyMap(),
    val budget: BudgetSummary? = null,
    /** Цель с наивысшим приоритетом и ближайшим сроком. */
    val goal: GoalProgress? = null,
    val portfolio: PortfolioSummary? = null,
    val forecast: BalanceForecast? = null,
    /** Дата актуальности курсов валют. */
    val ratesAsOf: String? = null,
    val userEmail: String = "",
    val showAdminMenu: Boolean = false,
    val unreadNotifications: Int = 0,
)

/** Строит состояние главного экрана по срезу данных локальной базы. */
fun buildDashboardState(snapshot: FinanceSnapshot, today: LocalDate, session: Session? = null, unread: Int = 0): DashboardUiState {
    val reports = ReportBuilder(snapshot.categoryMap, snapshot.converter, snapshot.baseCurrency)
    val month = YearMonth.from(today)
    val items = reports.select(snapshot.transactions, month.atDay(1), month.atEndOfMonth())
    val (income, expense) = reports.totals(items)
    val daily = reports.daily(items, month.atDay(1), today)
    val categories = reports.byCategory(items).take(MAX_CATEGORIES)

    val threshold = snapshot.profile.notificationSettings.budgetThresholdPercent
    val budgets = BudgetCalculator.overview(snapshot.budgets, snapshot.categoryMap, snapshot.transactions, today, snapshot.converter, threshold)
    val budget = if (budgets.isEmpty()) null else {
        val limit = budgets.fold(BigDecimal.ZERO) { sum, item ->
            sum + snapshot.converter.convert(item.budget.limitAmount, item.budget.currency, snapshot.baseCurrency)
        }
        val spent = budgets.fold(BigDecimal.ZERO) { sum, item ->
            sum + snapshot.converter.convert(item.spent, item.budget.currency, snapshot.baseCurrency)
        }
        val percent = if (limit.signum() > 0) spent.multiply(BigDecimal(100)).divide(limit, MathContext.DECIMAL64).toDouble() else 0.0
        BudgetSummary(limit, spent, percent, BudgetCalculator.classify(percent), budgets.count { it.level != BudgetLevel.OK })
    }

    val priorityRank = mapOf("high" to 0, "medium" to 1, "low" to 2)
    val goal = snapshot.goals.map { GoalCalculator.progress(it, snapshot.contributions, today) }
        .filter { !it.readOnly }
        .minWithOrNull(compareBy({ priorityRank[it.goal.priority] ?: 1 }, { it.goal.deadline }))

    val portfolio = PortfolioCalculator.portfolio(
        snapshot.trades, snapshot.prices, snapshot.profile.costingMethod, snapshot.converter, snapshot.baseCurrency,
    ).takeIf { it.positions.isNotEmpty() }?.let { PortfolioSummary(it.totalValue, it.totalProfit, it.totalProfitPercent, it.positions.size) }

    return DashboardUiState(
        loading = !snapshot.loaded,
        currency = snapshot.baseCurrency,
        month = month,
        totalBalance = BalanceCalculator.totalBalance(snapshot.accounts, snapshot.balances, snapshot.converter, snapshot.baseCurrency),
        income = income,
        expense = expense,
        dailyExpenses = daily.map { it.expense.toDouble() },
        dailyLabels = daily.map { it.label },
        categories = categories,
        categoryIcons = categories.associate { it.categoryId to snapshot.categoryMap[it.categoryId]?.icon.orEmpty() },
        budget = budget,
        goal = goal,
        portfolio = portfolio,
        forecast = reports.balanceForecast(snapshot.accounts, snapshot.transactions, today),
        ratesAsOf = snapshot.converter.asOf,
        userEmail = session?.email.orEmpty(),
        showAdminMenu = session?.isAnalyst == true,
        unreadNotifications = unread,
    )
}

private const val MAX_CATEGORIES = 5

class DashboardViewModel(finance: FinanceRepository, session: SessionStore, notifications: NotificationDao) : ViewModel() {
    val state: StateFlow<DashboardUiState> = combine(finance.snapshot, session.session, notifications.observeUnreadCount()) { snapshot, user, unread ->
        buildDashboardState(snapshot, Dates.toLocalDate(System.currentTimeMillis()), user, unread)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}
