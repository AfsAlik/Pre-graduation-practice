package ru.finhelper.app.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.FinanceSnapshot
import ru.finhelper.app.data.repo.ValidationError
import ru.finhelper.app.di.AppMessages
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.domain.BudgetCalculator
import ru.finhelper.app.domain.BudgetLevel
import ru.finhelper.app.domain.BudgetStatus
import ru.finhelper.app.domain.Periods
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.ConfirmDialog
import ru.finhelper.app.ui.components.EmptyState
import ru.finhelper.app.ui.components.FormDialog
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.MainTab
import ru.finhelper.app.ui.components.MoneyField
import ru.finhelper.app.ui.components.PercentBar
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.SelectField
import ru.finhelper.app.ui.components.StatusChip
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.dashboard.budgetTone
import ru.finhelper.app.ui.transactions.Option
import ru.finhelper.app.ui.transactions.categoryOptions
import ru.finhelper.app.ui.transactions.periodOptions
import java.math.BigDecimal
import java.time.LocalDate

/** Состояние экрана «Бюджет». */
data class BudgetUiState(
    val loading: Boolean = true,
    val statuses: List<BudgetStatus> = emptyList(),
    val currency: String = "RUB",
    /** Категории расходов, для которых можно установить лимит. */
    val categories: List<Option> = emptyList(),
    /** Порог предупреждения в процентах лимита (настройка пользователя). */
    val warningPercent: Int = BudgetCalculator.DEFAULT_WARNING_PERCENT,
)

fun buildBudgetState(snapshot: FinanceSnapshot, today: LocalDate): BudgetUiState {
    val threshold = snapshot.profile.notificationSettings.budgetThresholdPercent
    return BudgetUiState(
        loading = !snapshot.loaded,
        statuses = BudgetCalculator.overview(snapshot.budgets, snapshot.categoryMap, snapshot.transactions, today, snapshot.converter, threshold),
        currency = snapshot.baseCurrency,
        categories = categoryOptions(snapshot, BalanceCalculator.EXPENSE),
        warningPercent = threshold,
    )
}

/** Бюджетные лимиты и контроль их исполнения (п. 4.2.1.2 а, б ТЗ). */
class BudgetViewModel(private val finance: FinanceRepository, private val messages: AppMessages) : ViewModel() {
    val state: StateFlow<BudgetUiState> = finance.snapshot
        .map { buildBudgetState(it, Dates.toLocalDate(System.currentTimeMillis())) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    fun setLimit(id: String?, categoryId: String, period: String, limit: BigDecimal) {
        viewModelScope.launch {
            try {
                finance.setBudget(categoryId, period, limit, id)
                messages.show(R.string.budget_set)
            } catch (error: ValidationError) {
                messages.show(error.message.orEmpty())
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            finance.deleteBudget(id)
            messages.show(R.string.budget_deleted)
        }
    }
}

@Composable
fun BudgetRoute(viewModel: BudgetViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BudgetScreen(state, onSetLimit = viewModel::setLimit, onDelete = viewModel::delete)
}

/**
 * Экран «Бюджет»: лимиты по категориям с индикатором исполнения и статусом
 * «в пределах лимита», «превышение» или «критическое превышение».
 */
@Composable
fun BudgetScreen(
    state: BudgetUiState,
    onSetLimit: (id: String?, categoryId: String, period: String, limit: BigDecimal) -> Unit = { _, _, _, _ -> },
    onDelete: (String) -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BudgetStatus?>(null) }
    var deleting by remember { mutableStateOf<BudgetStatus?>(null) }
    val periods = periodOptions()
    AppScaffold(
        title = stringResource(R.string.tab_budget),
        tab = MainTab.BUDGET,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.budget_set_limit)) },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.statuses.isEmpty() -> EmptyState(stringResource(R.string.budget_empty), Modifier.padding(padding), Icons.Filled.PieChart)
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item {
                    Text(
                        stringResource(R.string.budget_threshold_note, state.warningPercent),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.statuses, key = { it.budget.id }) { status ->
                    BudgetCard(status, periods, onEdit = { editing = status }, onDelete = { deleting = status })
                }
                item { Spacer(Modifier.size(88.dp)) }
            }
        }
    }
    if (adding || editing != null) {
        LimitDialog(editing, state, periods, onSetLimit) { adding = false; editing = null }
    }
    deleting?.let { status ->
        ConfirmDialog(
            title = stringResource(R.string.budget_delete_title),
            text = stringResource(R.string.budget_delete_text, status.categoryName),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDelete(status.budget.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun BudgetCard(status: BudgetStatus, periods: List<Option>, onEdit: () -> Unit, onDelete: () -> Unit) {
    val currency = status.budget.currency
    val tone = budgetTone(status.level, status.warning)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.categoryName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.budget_period, periods.firstOrNull { it.id == status.budget.period }?.label.orEmpty(),
                        Dates.format(status.periodStart), Dates.format(status.periodEnd),
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TooltipIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), onDelete)
        }
        Spacer(Modifier.size(8.dp))
        PercentBar(status.percent, tone, description = stringResource(R.string.budget_progress_description, status.categoryName))
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.budget_spent_of, Money.format(status.spent, currency), Money.format(status.budget.limitAmount, currency)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (status.remaining.signum() >= 0) {
                stringResource(R.string.budget_remaining, Money.format(status.remaining, currency))
            } else {
                stringResource(R.string.budget_over, Money.format(status.remaining.negate(), currency))
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        // Статус передается значком и текстом, цвет лишь дополняет их (п. 4.4.5.4 ТЗ)
        val (label, icon) = when {
            status.level == BudgetLevel.CRITICAL -> status.level.title to Icons.Filled.Error
            status.level == BudgetLevel.EXCEEDED -> status.level.title to Icons.Filled.Warning
            status.warning -> stringResource(R.string.budget_near_limit) to Icons.Filled.Warning
            else -> status.level.title to Icons.Filled.CheckCircle
        }
        StatusChip(label, tone, icon = icon)
        OutlinedButton(onClick = onEdit, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.budget_set_limit))
        }
    }
}

/** Окно «Установить лимит»: категория, период и сумма лимита. */
@Composable
private fun LimitDialog(
    editing: BudgetStatus?,
    state: BudgetUiState,
    periods: List<Option>,
    onSetLimit: (String?, String, String, BigDecimal) -> Unit,
    onDismiss: () -> Unit,
) {
    var categoryId by remember { mutableStateOf(editing?.budget?.categoryId) }
    var period by remember { mutableStateOf(editing?.budget?.period ?: Periods.MONTH) }
    var amount by remember { mutableStateOf(Money.toInput(editing?.budget?.limitAmount)) }
    var categoryError by remember { mutableStateOf<String?>(null) }
    var amountError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = stringResource(R.string.budget_set_limit),
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = {
            val limit = Money.parse(amount)?.takeIf { it.signum() > 0 }
            categoryError = if (categoryId == null) "Выберите категорию расходов" else null
            amountError = if (limit == null) "Введите сумму лимита больше нуля" else null
            val category = categoryId
            if (limit != null && category != null) {
                onSetLimit(editing?.budget?.id, category, period, limit)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    ) {
        SelectField(
            stringResource(R.string.field_category), state.categories.firstOrNull { it.id == categoryId }, state.categories, Option::label,
            { categoryId = it.id; categoryError = null }, error = categoryError, enabled = editing == null,
        )
        SelectField(
            stringResource(R.string.budget_period_label), periods.firstOrNull { it.id == period }, periods, Option::label,
            { period = it.id }, enabled = editing == null,
        )
        MoneyField(
            amount, { amount = it; amountError = null }, stringResource(R.string.budget_limit), editing?.budget?.currency ?: state.currency,
            error = amountError,
        )
    }
}
