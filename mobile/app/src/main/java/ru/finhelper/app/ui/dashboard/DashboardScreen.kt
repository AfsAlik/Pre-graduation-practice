package ru.finhelper.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.domain.BudgetLevel
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.CategoryIcon
import ru.finhelper.app.ui.components.LabeledValue
import ru.finhelper.app.ui.components.LineChart
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.MainTab
import ru.finhelper.app.ui.components.PercentBar
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.StatusChip
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.components.toneColor
import ru.finhelper.app.ui.navigation.Routes
import ru.finhelper.app.ui.theme.parseColor

@Composable
fun DashboardRoute(viewModel: DashboardViewModel, onNavigate: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DashboardScreen(state, onNavigate)
}

/** Тон и подпись статуса бюджета: статус передается цветом, значком и текстом. */
fun budgetTone(level: BudgetLevel, warning: Boolean = false): Tone = when {
    level == BudgetLevel.CRITICAL -> Tone.NEGATIVE
    level == BudgetLevel.EXCEEDED -> Tone.NEGATIVE
    warning -> Tone.WARNING
    else -> Tone.POSITIVE
}

/**
 * Главный экран (дашборд): общий баланс, доходы и расходы месяца, график расходов,
 * расходы по категориям, индикатор бюджета, прогресс цели, сводка по портфелю и
 * прогноз остатка. Кнопка «+» открывает добавление транзакции (п. 4.4.5.7 ТЗ).
 *
 * @param onNavigate переход к экрану по его маршруту
 */
@Composable
fun DashboardScreen(state: DashboardUiState, onNavigate: (String) -> Unit = {}) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainMenu(state) { route ->
                scope.launch { drawerState.close() }
                onNavigate(route)
            }
        },
    ) {
        AppScaffold(
            title = stringResource(R.string.tab_dashboard),
            tab = MainTab.DASHBOARD,
            navigationIcon = {
                TooltipIconButton(Icons.Filled.Menu, stringResource(R.string.menu_open), { scope.launch { drawerState.open() } })
            },
            actions = {
                val notificationsLabel = stringResource(R.string.notifications_title)
                IconButton(onClick = { onNavigate(Routes.NOTIFICATIONS) }, modifier = Modifier.size(48.dp)) {
                    BadgedBox(badge = { if (state.unreadNotifications > 0) Badge { Text(state.unreadNotifications.toString()) } }) {
                        Icon(Icons.Filled.Notifications, contentDescription = notificationsLabel)
                    }
                }
                TooltipIconButton(Icons.Filled.AccountCircle, stringResource(R.string.profile_title), { onNavigate(Routes.PROFILE) })
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { onNavigate(Routes.transactionEdit()) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.transaction_add_title))
                }
            },
        ) { padding ->
            if (state.loading) LoadingBox(Modifier.padding(padding)) else DashboardContent(state, padding, onNavigate)
        }
    }
}

@Composable
private fun DashboardContent(state: DashboardUiState, padding: PaddingValues, onNavigate: (String) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        BalanceCard(state, onClick = { onNavigate(Routes.ACCOUNTS) })
        SectionCard(
            title = stringResource(R.string.dashboard_expense_chart),
            action = { TextButton(onClick = { onNavigate(Routes.REPORTS) }) { Text(stringResource(R.string.reports_title)) } },
        ) {
            LineChart(
                values = state.dailyExpenses,
                labels = state.dailyLabels,
                description = stringResource(R.string.dashboard_expense_chart_description, Money.format(state.expense, state.currency)),
                color = toneColor(Tone.NEGATIVE),
                maxLabel = state.dailyExpenses.maxOrNull()?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.chart_max, Money.format(it.toBigDecimal(), state.currency, kopecks = false)) },
            )
        }
        SectionCard(title = stringResource(R.string.dashboard_by_category)) {
            if (state.categories.isEmpty()) {
                Text(stringResource(R.string.dashboard_no_expenses), style = MaterialTheme.typography.bodyMedium)
            }
            state.categories.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    CategoryIcon(state.categoryIcons[category.categoryId].orEmpty(), parseColor(category.color))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(category.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            Money.formatPercent(category.share),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(Money.format(category.amount, state.currency), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        BudgetCard(state, onClick = { onNavigate(MainTab.BUDGET.route) })
        GoalCard(state, onClick = { onNavigate(MainTab.GOALS.route) })
        PortfolioCard(state, onClick = { onNavigate(MainTab.INVESTMENTS.route) })
        state.forecast?.let { forecast ->
            SectionCard(title = stringResource(R.string.dashboard_forecast)) {
                Text(
                    Money.format(forecast.forecastBalance, state.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (forecast.forecastBalance.signum() < 0) toneColor(Tone.NEGATIVE) else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                LabeledValue(stringResource(R.string.dashboard_forecast_income), Money.format(forecast.expectedIncome, state.currency, signed = true))
                LabeledValue(
                    stringResource(R.string.dashboard_forecast_expense),
                    Money.format(forecast.expectedExpense.negate(), state.currency),
                )
                Text(
                    stringResource(R.string.dashboard_forecast_note, forecast.daysLeft),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionCard(onClick = { onNavigate(Routes.RECOMMENDATIONS) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = toneColor(Tone.WARNING))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.recommendations_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.dashboard_recommendations_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.ratesAsOf?.let {
            Text(
                stringResource(R.string.dashboard_rates_as_of, Dates.format(Dates.parseDate(it))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(72.dp)) // место под плавающую кнопку
    }
}

@Composable
private fun BalanceCard(state: DashboardUiState, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.dashboard_total_balance), style = MaterialTheme.typography.labelLarge)
            Text(
                Money.format(state.totalBalance, state.currency),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.dashboard_month, Dates.monthTitle(state.month)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                FlowAmount(
                    Icons.AutoMirrored.Filled.TrendingUp, stringResource(R.string.dashboard_income),
                    Money.format(state.income, state.currency, signed = true), Tone.POSITIVE, Modifier.weight(1f),
                )
                FlowAmount(
                    Icons.AutoMirrored.Filled.TrendingDown, stringResource(R.string.dashboard_expense),
                    Money.format(state.expense.negate(), state.currency), Tone.NEGATIVE, Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FlowAmount(icon: ImageVector, label: String, amount: String, tone: Tone, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(icon, contentDescription = null, tint = toneColor(tone), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(amount, style = MaterialTheme.typography.titleMedium, color = toneColor(tone), maxLines = 1)
        }
    }
}

@Composable
private fun BudgetCard(state: DashboardUiState, onClick: () -> Unit) {
    SectionCard(title = stringResource(R.string.dashboard_budget), onClick = onClick) {
        val budget = state.budget
        if (budget == null) {
            Text(stringResource(R.string.dashboard_no_budget), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val tone = budgetTone(budget.level)
        PercentBar(budget.percent, tone, description = stringResource(R.string.dashboard_budget))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.budget_spent_of, Money.format(budget.spent, state.currency), Money.format(budget.limit, state.currency)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (budget.exceeded > 0) {
            StatusChip(stringResource(R.string.dashboard_budget_exceeded, budget.exceeded), Tone.NEGATIVE, icon = Icons.Filled.Warning)
        } else {
            StatusChip(BudgetLevel.OK.title, Tone.POSITIVE, icon = Icons.Filled.CheckCircle)
        }
    }
}

@Composable
private fun GoalCard(state: DashboardUiState, onClick: () -> Unit) {
    SectionCard(title = stringResource(R.string.dashboard_goal), onClick = onClick) {
        val progress = state.goal
        if (progress == null) {
            Text(stringResource(R.string.dashboard_no_goal), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text(progress.goal.name, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        PercentBar(progress.percent, Tone.NEUTRAL, description = progress.goal.name)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.goal_saved_of,
                Money.format(progress.saved, progress.goal.currency, kopecks = false),
                Money.format(progress.goal.targetAmount, progress.goal.currency, kopecks = false),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PortfolioCard(state: DashboardUiState, onClick: () -> Unit) {
    SectionCard(title = stringResource(R.string.dashboard_portfolio), onClick = onClick) {
        val portfolio = state.portfolio
        if (portfolio == null) {
            Text(stringResource(R.string.dashboard_no_portfolio), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val positive = portfolio.profit.signum() >= 0
        Text(Money.format(portfolio.value, state.currency), style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (positive) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                contentDescription = null, tint = toneColor(if (positive) Tone.POSITIVE else Tone.NEGATIVE), modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${Money.format(portfolio.profit, state.currency, signed = true)} (${Money.formatPercent(portfolio.profitPercent, signed = true)})",
                style = MaterialTheme.typography.bodyMedium, color = toneColor(if (positive) Tone.POSITIVE else Tone.NEGATIVE),
            )
        }
        Text(
            stringResource(R.string.dashboard_positions, portfolio.positions),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Боковое меню главного экрана. Административные разделы вынесены в отдельный
 * пункт, который показывается только аналитику и администратору (п. 4.4.5.9 ТЗ).
 */
@Composable
private fun MainMenu(state: DashboardUiState, onSelect: (String) -> Unit) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
            if (state.userEmail.isNotEmpty()) {
                Text(
                    state.userEmail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            MenuItem(Icons.Filled.Assessment, R.string.reports_title) { onSelect(Routes.REPORTS) }
            MenuItem(Icons.Filled.Lightbulb, R.string.recommendations_title) { onSelect(Routes.RECOMMENDATIONS) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MenuItem(Icons.Filled.AccountBalanceWallet, R.string.accounts_title) { onSelect(Routes.ACCOUNTS) }
            MenuItem(Icons.Filled.Category, R.string.categories_title) { onSelect(Routes.CATEGORIES) }
            MenuItem(Icons.Filled.FileCopy, R.string.templates_title) { onSelect(Routes.TEMPLATES) }
            MenuItem(Icons.Filled.EventRepeat, R.string.recurring_title) { onSelect(Routes.RECURRING) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MenuItem(Icons.Filled.AccountCircle, R.string.profile_title) { onSelect(Routes.PROFILE) }
            MenuItem(Icons.Filled.HelpOutline, R.string.help_title) { onSelect(Routes.HELP) }
            if (state.showAdminMenu) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                MenuItem(Icons.Filled.AdminPanelSettings, R.string.admin_title) { onSelect(Routes.ADMIN) }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, title: Int, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(title)) },
        selected = false,
        onClick = onClick,
    )
}
