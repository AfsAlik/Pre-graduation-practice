package ru.finhelper.app.ui.goals

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import ru.finhelper.app.domain.GoalCalculator
import ru.finhelper.app.domain.GoalProgress
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.AppTextField
import ru.finhelper.app.ui.components.ConfirmDialog
import ru.finhelper.app.ui.components.DateField
import ru.finhelper.app.ui.components.EmptyState
import ru.finhelper.app.ui.components.FormDialog
import ru.finhelper.app.ui.components.LabeledValue
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.MainTab
import ru.finhelper.app.ui.components.MoneyField
import ru.finhelper.app.ui.components.PercentBar
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.SelectField
import ru.finhelper.app.ui.components.StatusChip
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.transactions.Option
import java.math.BigDecimal
import java.time.LocalDate

/** Состояние экрана «Цели». */
data class GoalsUiState(
    val loading: Boolean = true,
    val goals: List<GoalProgress> = emptyList(),
    val currency: String = "RUB",
    val today: LocalDate = LocalDate.now(),
)

fun buildGoalsState(snapshot: FinanceSnapshot, today: LocalDate) = GoalsUiState(
    loading = !snapshot.loaded,
    goals = snapshot.goals.map { GoalCalculator.progress(it, snapshot.contributions, today) }
        .sortedWith(compareBy({ it.readOnly }, { it.goal.deadline })),
    currency = snapshot.baseCurrency,
    today = today,
)

/** Финансовые цели: постановка, накопления, прогресс и прогноз (п. 4.2.1.2 г–ж ТЗ). */
class GoalsViewModel(private val finance: FinanceRepository, private val messages: AppMessages) : ViewModel() {
    val state: StateFlow<GoalsUiState> = finance.snapshot
        .map { buildGoalsState(it, Dates.toLocalDate(System.currentTimeMillis())) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState())

    private fun run(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (error: ValidationError) {
                messages.show(error.message.orEmpty())
            }
        }
    }

    fun save(id: String?, name: String, target: BigDecimal, deadline: LocalDate, priority: String) = run {
        finance.saveGoal(id, name, target, deadline, priority)
        messages.show(if (id == null) R.string.goal_created else R.string.goal_saved)
    }

    fun delete(id: String) = run {
        finance.deleteGoal(id)
        messages.show(R.string.goal_deleted)
    }

    fun contribute(goalId: String, amount: BigDecimal, comment: String) = run {
        val achieved = finance.addContribution(goalId, amount, comment)
        messages.show(if (achieved) R.string.goal_achieved_message else R.string.goal_contribution_added)
    }
}

@Composable
fun GoalsRoute(viewModel: GoalsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GoalsScreen(state, onSave = viewModel::save, onDelete = viewModel::delete, onContribute = viewModel::contribute)
}

@Composable
private fun priorityOptions(): List<Option> = listOf(
    Option("high", stringResource(R.string.goal_priority_high)),
    Option("medium", stringResource(R.string.goal_priority_medium)),
    Option("low", stringResource(R.string.goal_priority_low)),
)

/**
 * Экран «Цели»: карточки целей с индикатором прогресса, прогнозируемой датой
 * достижения и рекомендуемой суммой ежемесячных накоплений. Достигнутая цель
 * доступна только для просмотра.
 */
@Composable
fun GoalsScreen(
    state: GoalsUiState,
    onSave: (id: String?, name: String, target: BigDecimal, deadline: LocalDate, priority: String) -> Unit = { _, _, _, _, _ -> },
    onDelete: (String) -> Unit = {},
    onContribute: (goalId: String, amount: BigDecimal, comment: String) -> Unit = { _, _, _ -> },
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GoalProgress?>(null) }
    var deleting by remember { mutableStateOf<GoalProgress?>(null) }
    var contributing by remember { mutableStateOf<GoalProgress?>(null) }
    val priorities = priorityOptions()
    AppScaffold(
        title = stringResource(R.string.tab_goals),
        tab = MainTab.GOALS,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add)) },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.goals.isEmpty() -> EmptyState(stringResource(R.string.goals_empty), Modifier.padding(padding), Icons.Filled.Flag)
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(state.goals, key = { it.goal.id }) { progress ->
                    GoalCard(
                        progress, priorities,
                        onContribute = { contributing = progress }, onEdit = { editing = progress }, onDelete = { deleting = progress },
                    )
                }
                item { Spacer(Modifier.size(88.dp)) }
            }
        }
    }
    if (adding || editing != null) GoalDialog(editing, state, priorities, onSave) { adding = false; editing = null }
    contributing?.let { progress -> ContributionDialog(progress, onContribute) { contributing = null } }
    deleting?.let { progress ->
        ConfirmDialog(
            title = stringResource(R.string.goal_delete_title),
            text = stringResource(R.string.goal_delete_text, progress.goal.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDelete(progress.goal.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun GoalCard(progress: GoalProgress, priorities: List<Option>, onContribute: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val goal = progress.goal
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(goal.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.goal_deadline_priority, Dates.format(Dates.parseDate(goal.deadline)),
                        priorities.firstOrNull { it.id == goal.priority }?.label.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!progress.readOnly) {
                TooltipIconButton(Icons.Filled.Edit, stringResource(R.string.action_edit), onEdit)
                TooltipIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), onDelete)
            }
        }
        Spacer(Modifier.size(8.dp))
        PercentBar(progress.percent, if (progress.readOnly) Tone.POSITIVE else Tone.NEUTRAL, description = goal.name)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.goal_saved_of, Money.format(progress.saved, goal.currency), Money.format(goal.targetAmount, goal.currency)),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (progress.readOnly) {
            Spacer(Modifier.size(8.dp))
            StatusChip(stringResource(R.string.goal_status_achieved), Tone.POSITIVE, icon = Icons.Filled.CheckCircle)
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.goal_read_only),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            return@SectionCard
        }
        LabeledValue(
            stringResource(R.string.goal_forecast_date),
            progress.forecastDate?.let(Dates::format) ?: stringResource(R.string.goal_no_forecast),
        )
        LabeledValue(stringResource(R.string.goal_recommended), Money.format(progress.recommendedMonthly, goal.currency))
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                progress.forecastDate == null -> StatusChip(stringResource(R.string.goal_status_started), Tone.NEUTRAL, icon = Icons.Filled.Schedule)
                !progress.onTrack -> StatusChip(stringResource(R.string.goal_status_behind), Tone.WARNING, icon = Icons.Filled.Warning)
                progress.percent >= ALMOST_DONE_PERCENT -> StatusChip(stringResource(R.string.goal_status_almost), Tone.POSITIVE, icon = Icons.Filled.CheckCircle)
                else -> StatusChip(stringResource(R.string.goal_status_on_track), Tone.POSITIVE, icon = Icons.Filled.CheckCircle)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onContribute, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.goal_contribute)) }
        }
    }
}

/** Доля накоплений, начиная с которой цель считается почти выполненной. */
private const val ALMOST_DONE_PERCENT = 80.0

@Composable
private fun GoalDialog(
    editing: GoalProgress?,
    state: GoalsUiState,
    priorities: List<Option>,
    onSave: (String?, String, BigDecimal, LocalDate, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val goal = editing?.goal
    var name by remember { mutableStateOf(goal?.name.orEmpty()) }
    var target by remember { mutableStateOf(Money.toInput(goal?.targetAmount)) }
    var deadline by remember { mutableStateOf(goal?.deadline?.let(Dates::parseDate) ?: state.today.plusYears(1)) }
    var priority by remember { mutableStateOf(goal?.priority ?: "medium") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var targetError by remember { mutableStateOf<String?>(null) }
    var deadlineError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = stringResource(if (goal == null) R.string.goal_add_title else R.string.goal_edit_title),
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = {
            val amount = Money.parse(target)?.takeIf { it.signum() > 0 }
            nameError = if (name.isBlank()) "Введите название цели" else null
            targetError = if (amount == null) "Введите целевую сумму больше нуля" else null
            deadlineError = if (goal == null && !deadline.isAfter(state.today)) "Выберите дату в будущем" else null
            if (amount != null && nameError == null && deadlineError == null) {
                onSave(goal?.id, name, amount, deadline, priority)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    ) {
        AppTextField(name, { name = it; nameError = null }, stringResource(R.string.field_name), error = nameError)
        MoneyField(target, { target = it; targetError = null }, stringResource(R.string.goal_target), goal?.currency ?: state.currency, error = targetError)
        DateField(stringResource(R.string.goal_deadline), deadline, { deadline = it; deadlineError = null }, error = deadlineError)
        SelectField(stringResource(R.string.goal_priority), priorities.firstOrNull { it.id == priority }, priorities, Option::label, { priority = it.id })
    }
}

/** Окно внесения накопления по цели. */
@Composable
private fun ContributionDialog(progress: GoalProgress, onContribute: (String, BigDecimal, String) -> Unit, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var amountError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = stringResource(R.string.goal_contribute_title, progress.goal.name),
        confirmLabel = stringResource(R.string.goal_contribute),
        onConfirm = {
            val parsed = Money.parse(amount)?.takeIf { it.signum() > 0 }
            if (parsed == null) amountError = "Введите сумму больше нуля" else {
                onContribute(progress.goal.id, parsed, comment)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    ) {
        Text(
            stringResource(R.string.goal_remaining, Money.format(progress.remaining, progress.goal.currency)),
            style = MaterialTheme.typography.bodyMedium,
        )
        MoneyField(amount, { amount = it; amountError = null }, stringResource(R.string.field_amount), progress.goal.currency, error = amountError)
        AppTextField(comment, { comment = it.take(300) }, stringResource(R.string.field_comment))
    }
}
