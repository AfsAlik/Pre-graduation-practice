package ru.finhelper.app.ui.reports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.remote.userMessage
import ru.finhelper.app.data.repo.FileRepository
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.FinanceSnapshot
import ru.finhelper.app.di.AppMessages
import ru.finhelper.app.domain.BudgetCalculator
import ru.finhelper.app.domain.BudgetStatus
import ru.finhelper.app.domain.Report
import ru.finhelper.app.domain.ReportBuilder
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.BarChart
import ru.finhelper.app.ui.components.BarGroup
import ru.finhelper.app.ui.components.ChartLegend
import ru.finhelper.app.ui.components.ChartSlice
import ru.finhelper.app.ui.components.DateField
import ru.finhelper.app.ui.components.DonutChart
import ru.finhelper.app.ui.components.LabeledValue
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.PercentBar
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.SelectField
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.components.toneColor
import ru.finhelper.app.ui.dashboard.budgetTone
import ru.finhelper.app.ui.theme.parseColor
import ru.finhelper.app.ui.transactions.Option
import ru.finhelper.app.ui.transactions.categoryOptions
import java.time.LocalDate

/** Готовые периоды отчета. */
enum class ReportPeriod(val title: Int) {
    WEEK(R.string.period_week),
    MONTH(R.string.period_month),
    QUARTER(R.string.asset_quarter),
    YEAR(R.string.period_year),
    CUSTOM(R.string.period_custom),
}

/** Параметры отчета: период и фильтры по счету и категории (п. 4.2.1.4 в ТЗ). */
data class ReportFilter(
    val period: ReportPeriod = ReportPeriod.MONTH,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
) {
    fun bounds(today: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        ReportPeriod.WEEK -> today.minusDays(6) to today
        ReportPeriod.MONTH -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
        ReportPeriod.QUARTER -> today.minusMonths(2).withDayOfMonth(1) to today
        ReportPeriod.YEAR -> today.minusMonths(11).withDayOfMonth(1) to today
        ReportPeriod.CUSTOM -> {
            val start = from ?: today.withDayOfMonth(1)
            start to (to ?: today).coerceAtLeast(start)
        }
    }
}

data class ReportsUiState(
    val loading: Boolean = true,
    val filter: ReportFilter = ReportFilter(),
    val report: Report? = null,
    val budgets: List<BudgetStatus> = emptyList(),
    val currency: String = "RUB",
    val accounts: List<Option> = emptyList(),
    val categories: List<Option> = emptyList(),
    /** Выполняется экспорт отчета. */
    val exporting: Boolean = false,
)

fun buildReportsState(snapshot: FinanceSnapshot, filter: ReportFilter, today: LocalDate): ReportsUiState {
    val (start, end) = filter.bounds(today)
    val builder = ReportBuilder(snapshot.categoryMap, snapshot.converter, snapshot.baseCurrency)
    return ReportsUiState(
        loading = !snapshot.loaded,
        filter = filter,
        report = builder.report(snapshot.transactions, start, end, setOfNotNull(filter.accountId), setOfNotNull(filter.categoryId)),
        budgets = BudgetCalculator.overview(
            snapshot.budgets, snapshot.categoryMap, snapshot.transactions, minOf(end, today), snapshot.converter,
            snapshot.profile.notificationSettings.budgetThresholdPercent,
        ),
        currency = snapshot.baseCurrency,
        accounts = snapshot.accounts.map { Option(it.id, it.name) },
        categories = categoryOptions(snapshot, null),
    )
}

/** Аналитические отчеты строятся по локальной базе и доступны без сети; экспорт выполняет сервер. */
class ReportsViewModel(
    finance: FinanceRepository,
    private val files: FileRepository,
    private val messages: AppMessages,
) : ViewModel() {
    private val filter = MutableStateFlow(ReportFilter())
    private val exporting = MutableStateFlow(false)

    val state: StateFlow<ReportsUiState> = combine(finance.snapshot, filter, exporting) { snapshot, current, busy ->
        buildReportsState(snapshot, current, Dates.toLocalDate(System.currentTimeMillis())).copy(exporting = busy)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun updateFilter(transform: (ReportFilter) -> ReportFilter) = filter.update(transform)

    /** Экспорт отчета в PDF или XLSX (п. 4.2.1.7 б ТЗ). */
    fun export(target: Uri, format: String) {
        val current = filter.value
        val (start, end) = current.bounds(Dates.toLocalDate(System.currentTimeMillis()))
        exporting.value = true
        viewModelScope.launch {
            files.exportReport(target, format, start, end, listOfNotNull(current.accountId), listOfNotNull(current.categoryId))
                .onSuccess { messages.show(R.string.file_saved) }
                .onFailure { messages.show(it.userMessage()) }
            exporting.value = false
        }
    }
}

@Composable
fun ReportsRoute(viewModel: ReportsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { viewModel.export(it, "pdf") }
    }
    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri -> uri?.let { viewModel.export(it, "xlsx") } }
    ReportsScreen(
        state = state,
        onBack = onBack,
        onFilterChange = viewModel::updateFilter,
        onExport = { format -> if (format == "pdf") pdfLauncher.launch("Отчет.pdf") else xlsxLauncher.launch("Отчет.xlsx") },
    )
}

/**
 * Экран «Отчеты»: выбор периода, фильтры по счету и категории, диаграмма расходов
 * по категориям, динамика доходов и расходов, исполнение бюджета и текстовая сводка.
 */
@Composable
fun ReportsScreen(
    state: ReportsUiState,
    onBack: () -> Unit = {},
    onFilterChange: ((ReportFilter) -> ReportFilter) -> Unit = {},
    onExport: (String) -> Unit = {},
) {
    var exportMenu by remember { mutableStateOf(false) }
    val report = state.report
    AppScaffold(
        title = stringResource(R.string.reports_title),
        onBack = onBack,
        actions = {
            Box {
                TooltipIconButton(Icons.Filled.FileDownload, stringResource(R.string.action_export), { exportMenu = true })
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_pdf)) }, onClick = { exportMenu = false; onExport("pdf") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_xlsx)) }, onClick = { exportMenu = false; onExport("xlsx") })
                }
            }
        },
    ) { padding ->
        if (state.loading || report == null) {
            LoadingBox(Modifier.padding(padding))
            return@AppScaffold
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            if (state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = state.filter.period == period,
                        onClick = { onFilterChange { it.copy(period = period) } },
                        label = { Text(stringResource(period.title)) },
                    )
                }
            }
            Text(
                stringResource(R.string.reports_period, Dates.format(report.start), Dates.format(report.end)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.filter.period == ReportPeriod.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField(stringResource(R.string.date_from), report.start, { value -> onFilterChange { it.copy(from = value) } }, Modifier.weight(1f))
                    DateField(stringResource(R.string.date_to), report.end, { value -> onFilterChange { it.copy(to = value) } }, Modifier.weight(1f))
                }
            }
            val all = Option("", stringResource(R.string.all_items))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val accounts = listOf(all) + state.accounts
                SelectField(
                    stringResource(R.string.field_account), accounts.firstOrNull { it.id == state.filter.accountId.orEmpty() }, accounts,
                    Option::label, { option -> onFilterChange { it.copy(accountId = option.id.ifEmpty { null }) } }, Modifier.weight(1f),
                )
                val categories = listOf(all) + state.categories
                SelectField(
                    stringResource(R.string.field_category), categories.firstOrNull { it.id == state.filter.categoryId.orEmpty() }, categories,
                    Option::label, { option -> onFilterChange { it.copy(categoryId = option.id.ifEmpty { null }) } }, Modifier.weight(1f),
                )
            }
            SectionCard(title = stringResource(R.string.reports_totals)) {
                LabeledValue(
                    stringResource(R.string.dashboard_income), Money.format(report.income, state.currency, signed = true),
                    valueColor = toneColor(Tone.POSITIVE),
                )
                LabeledValue(
                    stringResource(R.string.dashboard_expense), Money.format(report.expense.negate(), state.currency),
                    valueColor = toneColor(Tone.NEGATIVE),
                )
                LabeledValue(
                    stringResource(R.string.reports_balance), Money.format(report.income - report.expense, state.currency, signed = true), bold = true,
                )
                LabeledValue(stringResource(R.string.reports_operations), report.operations.toString())
            }
            SectionCard(title = stringResource(R.string.dashboard_by_category)) {
                if (report.expensesByCategory.isEmpty()) {
                    Text(stringResource(R.string.reports_no_expenses), style = MaterialTheme.typography.bodyMedium)
                } else {
                    val slices = report.expensesByCategory.map { row ->
                        ChartSlice(row.name, row.amount.toDouble(), parseColor(row.color), "${Money.format(row.amount, state.currency, kopecks = false)} · ${Money.formatPercent(row.share)}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(
                            slices, stringResource(R.string.reports_category_chart_description), diameter = 128.dp,
                            centerText = Money.format(report.expense, state.currency, kopecks = false),
                            centerCaption = stringResource(R.string.dashboard_expense),
                        )
                        Spacer(Modifier.width(12.dp))
                        ChartLegend(slices.take(MAX_LEGEND_ROWS), Modifier.weight(1f))
                    }
                }
            }
            SectionCard(title = stringResource(R.string.reports_dynamics)) {
                val income = toneColor(Tone.POSITIVE)
                val expense = toneColor(Tone.NEGATIVE)
                val max = report.dynamics.maxOfOrNull { maxOf(it.income, it.expense) }
                BarChart(
                    groups = report.dynamics.map { BarGroup(it.label, listOf(it.income.toDouble(), it.expense.toDouble())) },
                    colors = listOf(income, expense),
                    description = stringResource(R.string.reports_dynamics_description),
                    maxLabel = max?.takeIf { it.signum() > 0 }?.let { stringResource(R.string.chart_max, Money.format(it, state.currency, kopecks = false)) },
                )
                Spacer(Modifier.size(4.dp))
                ChartLegend(
                    listOf(
                        ChartSlice(stringResource(R.string.dashboard_income), 0.0, income, Money.format(report.income, state.currency, kopecks = false)),
                        ChartSlice(stringResource(R.string.dashboard_expense), 0.0, expense, Money.format(report.expense, state.currency, kopecks = false)),
                    )
                )
            }
            if (state.budgets.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.dashboard_budget)) {
                    state.budgets.forEach { status ->
                        Text(
                            "${status.categoryName} · ${status.level.title}",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                        )
                        PercentBar(status.percent, budgetTone(status.level, status.warning), description = status.categoryName)
                    }
                }
            }
            SectionCard(title = stringResource(R.string.reports_summary)) {
                Text(report.summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val MAX_LEGEND_ROWS = 6
