package ru.finhelper.app.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.CategoryEntity
import ru.finhelper.app.data.local.RecurringPaymentEntity
import ru.finhelper.app.data.local.TemplateEntity
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.domain.Periods
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.AppTextField
import ru.finhelper.app.ui.components.CategoryIcon
import ru.finhelper.app.ui.components.ConfirmDialog
import ru.finhelper.app.ui.components.DateField
import ru.finhelper.app.ui.components.EmptyState
import ru.finhelper.app.ui.components.FormDialog
import ru.finhelper.app.ui.components.LabeledValue
import ru.finhelper.app.ui.components.MoneyField
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.SelectField
import ru.finhelper.app.ui.components.StatusChip
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.components.toneColor
import ru.finhelper.app.ui.theme.parseColor

private const val FIELD_REQUIRED = "Заполните поле"
private const val AMOUNT_REQUIRED = "Введите сумму больше нуля"

/** Названия видов операций и периодов для переключателей и списков. */
@Composable
fun periodOptions(): List<Option> = listOf(
    Option(Periods.WEEK, stringResource(R.string.period_week)),
    Option(Periods.MONTH, stringResource(R.string.period_month)),
    Option(Periods.YEAR, stringResource(R.string.period_year)),
)

@Composable
private fun AddButton(description: Int, onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick) { Icon(Icons.Filled.Add, contentDescription = stringResource(description)) }
}

// ---------------------------------------------------------------------------- счета

@Composable
fun accountTypeOptions(): List<Option> = listOf(
    Option("cash", stringResource(R.string.account_type_cash)),
    Option("bank", stringResource(R.string.account_type_bank)),
    Option("credit", stringResource(R.string.account_type_credit)),
    Option("investment", stringResource(R.string.account_type_investment)),
)

private fun accountIcon(type: String): ImageVector = when (type) {
    "bank" -> Icons.Filled.AccountBalance
    "credit" -> Icons.Filled.CreditCard
    "investment" -> Icons.AutoMirrored.Filled.ShowChart
    else -> Icons.Filled.AccountBalanceWallet
}

/**
 * Экран «Счета»: счета разных типов и валют с рассчитанными балансами и
 * пересчетом в базовую валюту по сохраненным курсам (п. 4.2.1.1 е, ж ТЗ).
 */
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onBack: () -> Unit = {},
    onSave: (id: String?, name: String, type: String, currency: String, initial: BigDecimal, archived: Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onDelete: (String) -> Unit = {},
) {
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AccountEntity?>(null) }
    val types = accountTypeOptions()
    AppScaffold(
        title = stringResource(R.string.accounts_title),
        onBack = onBack,
        floatingActionButton = { AddButton(R.string.accounts_add) { adding = true } },
    ) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                SectionCard {
                    Text(stringResource(R.string.accounts_total), style = MaterialTheme.typography.labelLarge)
                    Text(Money.format(state.total, state.baseCurrency), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        state.ratesAsOf?.let { stringResource(R.string.accounts_rates_as_of, Dates.format(Dates.parseDate(it))) }
                            ?: stringResource(R.string.accounts_rates_default),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.rows.isEmpty() && !state.loading) {
                item { EmptyState(stringResource(R.string.accounts_empty), icon = Icons.Filled.AccountBalanceWallet) }
            }
            items(state.rows, key = { it.account.id }) { row ->
                SectionCard(onClick = { editing = row.account }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(accountIcon(row.account.type), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.account.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                types.firstOrNull { it.id == row.account.type }?.label.orEmpty() +
                                    if (row.account.isArchived) " · " + stringResource(R.string.accounts_archived_label) else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                Money.format(row.balance, row.account.currency),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (row.balance.signum() < 0) toneColor(Tone.NEGATIVE) else MaterialTheme.colorScheme.onSurface,
                            )
                            if (row.account.currency != state.baseCurrency) {
                                Text(
                                    stringResource(R.string.accounts_in_base, Money.format(row.balanceBase, state.baseCurrency)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TooltipIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), { deleting = row.account })
                    }
                }
            }
            item { Spacer(Modifier.size(72.dp)) }
        }
    }
    if (adding || editing != null) {
        AccountDialog(editing, types, state.currencies, state.baseCurrency, onSave) { adding = false; editing = null }
    }
    deleting?.let { account ->
        ConfirmDialog(
            title = stringResource(R.string.accounts_delete_title),
            text = stringResource(R.string.accounts_delete_text, account.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDelete(account.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun AccountDialog(
    account: AccountEntity?,
    types: List<Option>,
    currencies: List<String>,
    baseCurrency: String,
    onSave: (String?, String, String, String, BigDecimal, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(account?.name.orEmpty()) }
    var type by remember { mutableStateOf(account?.type ?: "cash") }
    var currency by remember { mutableStateOf(account?.currency ?: baseCurrency) }
    var initial by remember { mutableStateOf(Money.toInput(account?.initialBalance)) }
    var archived by remember { mutableStateOf(account?.isArchived ?: false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = stringResource(if (account == null) R.string.accounts_add else R.string.accounts_edit),
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = {
            if (name.isBlank()) {
                nameError = FIELD_REQUIRED
            } else {
                onSave(account?.id, name, type, currency, Money.parse(initial) ?: BigDecimal.ZERO, archived)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    ) {
        AppTextField(name, { name = it; nameError = null }, stringResource(R.string.field_name), error = nameError)
        SelectField(stringResource(R.string.accounts_type), types.firstOrNull { it.id == type }, types, Option::label, { type = it.id })
        SelectField(stringResource(R.string.accounts_currency), currency, currencies, { "$it (${Money.symbol(it)})" }, { currency = it })
        MoneyField(initial, { initial = it }, stringResource(R.string.accounts_initial_balance), currency)
        if (account != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(archived, role = Role.Switch) { archived = it },
            ) {
                Text(stringResource(R.string.accounts_archived), modifier = Modifier.weight(1f))
                Switch(checked = archived, onCheckedChange = null)
            }
        }
    }
}

// ---------------------------------------------------------------------------- категории

/**
 * Экран «Категории»: справочник категорий и подкатегорий доходов и расходов.
 * Системные категории доступны только для просмотра, пользовательские можно
 * создавать, изменять и удалять (п. 4.2.1.1 д ТЗ).
 */
@Composable
fun CategoriesScreen(
    state: CategoriesUiState,
    onBack: () -> Unit = {},
    onSave: (id: String?, name: String, kind: String, parentId: String?) -> Unit = { _, _, _, _ -> },
    onDelete: (String) -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }
    val kind = if (tab == 0) BalanceCalculator.EXPENSE else BalanceCalculator.INCOME
    val list = if (tab == 0) state.expense else state.income
    AppScaffold(
        title = stringResource(R.string.categories_title),
        onBack = onBack,
        floatingActionButton = { AddButton(R.string.categories_add) { adding = true } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.categories_expenses)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.categories_income)) })
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { category ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(enabled = !category.isSystem) { editing = category }
                            .padding(start = if (category.parentId != null) 48.dp else 16.dp, end = 8.dp),
                    ) {
                        CategoryIcon(category.icon, parseColor(category.color))
                        Spacer(Modifier.width(12.dp))
                        Text(category.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (category.isSystem) {
                            StatusChip(stringResource(R.string.categories_system), Tone.NEUTRAL, icon = Icons.Filled.Lock)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            TooltipIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), { deleting = category })
                        }
                    }
                }
                item { Spacer(Modifier.size(72.dp)) }
            }
        }
    }
    if (adding || editing != null) {
        CategoryDialog(editing, kind, list.filter { it.parentId == null && it.id != editing?.id }, onSave) { adding = false; editing = null }
    }
    deleting?.let { category ->
        ConfirmDialog(
            title = stringResource(R.string.categories_delete_title),
            text = stringResource(R.string.categories_delete_text, category.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDelete(category.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun CategoryDialog(
    category: CategoryEntity?,
    kind: String,
    parents: List<CategoryEntity>,
    onSave: (String?, String, String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(category?.name.orEmpty()) }
    var parentId by remember { mutableStateOf(category?.parentId) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val options = listOf(Option("", stringResource(R.string.categories_no_parent))) + parents.map { Option(it.id, it.name) }
    FormDialog(
        title = stringResource(if (category == null) R.string.categories_add else R.string.categories_edit),
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = {
            if (name.isBlank()) nameError = FIELD_REQUIRED else {
                onSave(category?.id, name, category?.kind ?: kind, parentId)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    ) {
        AppTextField(name, { name = it; nameError = null }, stringResource(R.string.field_name), error = nameError)
        SelectField(
            stringResource(R.string.categories_parent), options.firstOrNull { it.id == parentId.orEmpty() }, options, Option::label,
            { parentId = it.id.ifEmpty { null } },
        )
    }
}

// ---------------------------------------------------------------------------- шаблоны

/** Экран «Шаблоны»: заготовки типовых операций для быстрого ввода (п. 4.2.1.1 б ТЗ). */
@Composable
fun TemplatesScreen(
    state: TemplatesUiState,
    onBack: () -> Unit = {},
    onSave: (id: String?, name: String, kind: String, amount: BigDecimal, accountId: String?, categoryId: String?, comment: String) -> Unit =
        { _, _, _, _, _, _, _ -> },
    onDelete: (String) -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TemplateEntity?>(null) }
    var deleting by remember { mutableStateOf<TemplateEntity?>(null) }
    AppScaffold(
        title = stringResource(R.string.templates_title),
        onBack = onBack,
        floatingActionButton = { AddButton(R.string.templates_add) { adding = true } },
    ) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (state.templates.isEmpty()) item { EmptyState(stringResource(R.string.templates_empty), icon = Icons.Filled.FileCopy) }
            items(state.templates, key = { it.id }) { template ->
                SectionCard(onClick = { editing = template }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(template.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                state.categoryNames[template.categoryId].orEmpty(),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val income = template.kind == BalanceCalculator.INCOME
                        Text(
                            Money.format(if (income) template.amount else template.amount.negate(), template.currency, signed = true),
                            style = MaterialTheme.typography.titleMedium, color = toneColor(if (income) Tone.POSITIVE else Tone.NEGATIVE),
                        )
                        TooltipIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), { deleting = template })
                    }
                }
            }
            item { Spacer(Modifier.size(72.dp)) }
        }
    }
    if (adding || editing != null) TemplateDialog(editing, state, onSave) { adding = false; editing = null }
    deleting?.let { template ->
        ConfirmDialog(
            title = stringResource(R.string.templates_delete_title),
            text = stringResource(R.string.templates_delete_text, template.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDelete(template.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateDialog(
    template: TemplateEntity?,
    state: TemplatesUiState,
    onSave: (String?, String, String, BigDecimal, String?, String?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(template?.name.orEmpty()) }
    var kind by remember { mutableStateOf(template?.kind ?: BalanceCalculator.EXPENSE) }
    var amount by remember { mutableStateOf(Money.toInput(template?.amount)) }
    var accountId by remember { mutableStateOf(template?.accountId) }
    var categoryId by remember { mutableStateOf(template?.categoryId) }
    var comment by remember { mutableStateOf(template?.comment.orEmpty()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val categories = if (kind == BalanceCalculator.EXPENSE) state.expenseCategories else state.incomeCategories
    FormDialog(
        title = stringResource(if (template == null) R.string.templates_add else R.string.templates_edit),
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = {
            if (name.isBlank()) nameError = FIELD_REQUIRED else {
                onSave(template?.id, name, kind, Money.parse(amount) ?: BigDecimal.ZERO, accountId, categoryId, comment)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    ) {
        AppTextField(name, { name = it; nameError = null }, stringResource(R.string.field_name), error = nameError)
        val kinds = listOf(BalanceCalculator.EXPENSE to R.string.kind_expense, BalanceCalculator.INCOME to R.string.kind_income)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { index, (value, title) ->
                SegmentedButton(
                    selected = kind == value,
                    onClick = { if (kind != value) { kind = value; categoryId = null } },
                    shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                ) { Text(stringResource(title)) }
            }
        }
        MoneyField(amount, { amount = it }, stringResource(R.string.field_amount), state.currency)
        SelectField(stringResource(R.string.field_account), state.accounts.firstOrNull { it.id == accountId }, state.accounts, Option::label, { accountId = it.id })
        SelectField(stringResource(R.string.field_category), categories.firstOrNull { it.id == categoryId }, categories, Option::label, { categoryId = it.id })
        AppTextField(comment, { comment = it }, stringResource(R.string.field_comment))
    }
}

// ---------------------------------------------------------------------------- регулярные платежи

/**
 * Экран «Регулярные платежи»: платежи с датой следующего платежа и напоминанием
 * (п. 4.2.1.1 к ТЗ). Кнопка «Оплачено» создает расход и переносит дату.
 */
@Composable
fun RecurringScreen(
    state: TemplatesUiState,
    onBack: () -> Unit = {},
    onSave: (id: String?, name: String, amount: BigDecimal, accountId: String?, categoryId: String?, period: String, nextDate: LocalDate, remindDays: Int) -> Unit =
        { _, _, _, _, _, _, _, _ -> },
    onDelete: (String) -> Unit = {},
    onPaid: (String) -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RecurringPaymentEntity?>(null) }
    var deleting by remember { mutableStateOf<RecurringPaymentEntity?>(null) }
    val periods = periodOptions()
    AppScaffold(
        title = stringResource(R.string.recurring_title),
        onBack = onBack,
        floatingActionButton = { AddButton(R.string.recurring_add) { adding = true } },
    ) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (state.payments.isEmpty()) item { EmptyState(stringResource(R.string.recurring_empty), icon = Icons.Filled.EventRepeat) }
            items(state.payments, key = { it.id }) { payment ->
                val date = Dates.parseDate(payment.nextDate)
                val daysLeft = ChronoUnit.DAYS.between(state.today, date)
                SectionCard(onClick = { editing = payment }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(payment.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(periods.firstOrNull { it.id == payment.period }?.label, state.categoryNames[payment.categoryId])
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Money.format(payment.amount, payment.currency), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.recurring_next, Dates.format(date)), style = MaterialTheme.typography.bodyMedium)
                            when {
                                daysLeft < 0 -> StatusChip(stringResource(R.string.recurring_overdue), Tone.NEGATIVE)
                                daysLeft == 0L -> StatusChip(stringResource(R.string.recurring_due_today), Tone.WARNING)
                            }
                        }
                        OutlinedButton(onClick = { onPaid(payment.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.recurring_paid))
                        }
                        TooltipIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), { deleting = payment })
                    }
                }
            }
            item { Spacer(Modifier.size(72.dp)) }
        }
    }
    if (adding || editing != null) PaymentDialog(editing, state, periods, onSave) { adding = false; editing = null }
    deleting?.let { payment ->
        ConfirmDialog(
            title = stringResource(R.string.recurring_delete_title),
            text = stringResource(R.string.recurring_delete_text, payment.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDelete(payment.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PaymentDialog(
    payment: RecurringPaymentEntity?,
    state: TemplatesUiState,
    periods: List<Option>,
    onSave: (String?, String, BigDecimal, String?, String?, String, LocalDate, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(payment?.name.orEmpty()) }
    var amount by remember { mutableStateOf(Money.toInput(payment?.amount)) }
    var accountId by remember { mutableStateOf(payment?.accountId ?: state.accounts.firstOrNull()?.id) }
    var categoryId by remember { mutableStateOf(payment?.categoryId) }
    var period by remember { mutableStateOf(payment?.period ?: Periods.MONTH) }
    var nextDate by remember { mutableStateOf(payment?.nextDate?.let(Dates::parseDate) ?: state.today.plusMonths(1)) }
    var remindDays by remember { mutableStateOf((payment?.remindDaysBefore ?: 1).toString()) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var amountError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = stringResource(if (payment == null) R.string.recurring_add else R.string.recurring_edit),
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = {
            val parsed = Money.parse(amount)?.takeIf { it.signum() > 0 }
            nameError = if (name.isBlank()) FIELD_REQUIRED else null
            amountError = if (parsed == null) AMOUNT_REQUIRED else null
            if (parsed != null && nameError == null) {
                onSave(payment?.id, name, parsed, accountId, categoryId, period, nextDate, remindDays.toIntOrNull() ?: 1)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    ) {
        AppTextField(name, { name = it; nameError = null }, stringResource(R.string.field_name), error = nameError)
        MoneyField(amount, { amount = it; amountError = null }, stringResource(R.string.field_amount), state.currency, error = amountError)
        SelectField(stringResource(R.string.field_account), state.accounts.firstOrNull { it.id == accountId }, state.accounts, Option::label, { accountId = it.id })
        SelectField(
            stringResource(R.string.field_category), state.expenseCategories.firstOrNull { it.id == categoryId }, state.expenseCategories,
            Option::label, { categoryId = it.id },
        )
        SelectField(stringResource(R.string.recurring_period), periods.firstOrNull { it.id == period }, periods, Option::label, { period = it.id })
        DateField(stringResource(R.string.recurring_next_date), nextDate, { nextDate = it })
        AppTextField(
            remindDays, { value -> remindDays = value.filter(Char::isDigit).take(2) }, stringResource(R.string.recurring_remind_days),
            keyboardType = KeyboardType.Number, alignEnd = true,
        )
    }
}

// ---------------------------------------------------------------------------- корзина

/** Экран «Корзина»: удаленные транзакции можно восстановить в течение 30 дней (п. 4.2.1.1 и ТЗ). */
@Composable
fun TrashScreen(rows: List<TrashRow>, onBack: () -> Unit = {}, onRestore: (String) -> Unit = {}) {
    AppScaffold(title = stringResource(R.string.trash_title), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.trash_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (rows.isEmpty()) item { EmptyState(stringResource(R.string.trash_empty), icon = Icons.Filled.Delete) }
            items(rows, key = { it.row.id }) { item ->
                Column {
                    TransactionRowView(item.row) {
                        TooltipIconButton(Icons.Filled.Restore, stringResource(R.string.action_restore), { onRestore(item.row.id) })
                    }
                    LabeledValue("", stringResource(R.string.trash_days_left, item.daysLeft), Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
