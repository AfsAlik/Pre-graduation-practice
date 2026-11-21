package ru.finhelper.app.ui.transactions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.CategoryIcon
import ru.finhelper.app.ui.components.ConfirmDialog
import ru.finhelper.app.ui.components.DateField
import ru.finhelper.app.ui.components.EmptyState
import ru.finhelper.app.ui.components.FormDialog
import ru.finhelper.app.ui.components.GroupTitle
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.MainTab
import ru.finhelper.app.ui.components.MoneyField
import ru.finhelper.app.ui.components.SelectField
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.components.toneColor
import ru.finhelper.app.ui.theme.parseColor

/** Типы файлов банковских выписок, предлагаемые системным диалогом выбора файла. */
private val STATEMENT_TYPES = arrayOf("text/*", "application/octet-stream", "application/x-ofx", "application/vnd.intu.qfx", "application/qif", "*/*")

@Composable
fun TransactionsRoute(viewModel: TransactionsViewModel, onOpen: (String?) -> Unit, onTrash: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.chooseImportFile(it) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { viewModel.export(it, "pdf") }
    }
    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri -> uri?.let { viewModel.export(it, "xlsx") } }
    TransactionsScreen(
        state = state,
        onFilterChange = viewModel::updateFilter,
        onOpen = onOpen,
        onDelete = viewModel::delete,
        onImportClick = { importLauncher.launch(STATEMENT_TYPES) },
        onImportConfirm = viewModel::importStatement,
        onImportCancel = { viewModel.chooseImportFile(null) },
        onExportClick = { format -> if (format == "pdf") pdfLauncher.launch("Операции.pdf") else xlsxLauncher.launch("Операции.xlsx") },
        onTrash = onTrash,
    )
}

/**
 * Экран «Транзакции»: история операций с фильтрами по периоду, категории, счету и
 * сумме, поиском, удалением жестом смахивания, импортом выписки и экспортом.
 */
@Composable
fun TransactionsScreen(
    state: TransactionsUiState,
    onFilterChange: ((TransactionFilter) -> TransactionFilter) -> Unit = {},
    onOpen: (String?) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onImportClick: () -> Unit = {},
    onImportConfirm: (String) -> Unit = {},
    onImportCancel: () -> Unit = {},
    onExportClick: (String) -> Unit = {},
    onTrash: () -> Unit = {},
    filtersInitiallyExpanded: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var filtersExpanded by rememberSaveable { mutableStateOf(filtersInitiallyExpanded) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    AppScaffold(
        title = stringResource(R.string.tab_transactions),
        tab = MainTab.TRANSACTIONS,
        actions = {
            Box {
                TooltipIconButton(Icons.Filled.MoreVert, stringResource(R.string.action_more), { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.import_statement)) }, onClick = { menuOpen = false; onImportClick() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_pdf)) }, onClick = { menuOpen = false; onExportClick("pdf") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_xlsx)) }, onClick = { menuOpen = false; onExportClick("xlsx") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.trash_title)) }, onClick = { menuOpen = false; onTrash() })
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpen(null) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.transaction_add_title))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FilterPanel(state, filtersExpanded, { filtersExpanded = !filtersExpanded }, onFilterChange)
            when {
                state.loading -> LoadingBox()
                state.groups.isEmpty() -> EmptyState(stringResource(R.string.transactions_empty), icon = Icons.AutoMirrored.Filled.ReceiptLong)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                stringResource(R.string.transactions_count, state.count),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                            )
                            Text(
                                Money.format(state.income, state.currency, signed = true),
                                style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.POSITIVE),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                Money.format(state.expense.negate(), state.currency),
                                style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.NEGATIVE),
                            )
                        }
                    }
                    state.groups.forEach { group ->
                        item(key = group.date.toString()) {
                            GroupTitle(Dates.formatDayMonth(group.date), Modifier.padding(horizontal = 16.dp))
                        }
                        items(group.rows, key = { it.id }) { row ->
                            SwipeToDeleteRow(row, onClick = { onOpen(row.id) }, onSwiped = { deleteId = row.id })
                        }
                    }
                    item { Spacer(Modifier.size(88.dp)) }
                }
            }
        }
    }
    deleteId?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.transaction_delete_title),
            text = stringResource(R.string.transaction_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDelete(id); deleteId = null },
            onDismiss = { deleteId = null },
        )
    }
    if (state.importFile != null) ImportDialog(state.accounts, onImportConfirm, onImportCancel)
}

@Composable
private fun FilterPanel(
    state: TransactionsUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onFilterChange: ((TransactionFilter) -> TransactionFilter) -> Unit,
) {
    val filter = state.filter
    val all = Option("", stringResource(R.string.all_items))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = { value -> onFilterChange { it.copy(query = value) } },
            label = { Text(stringResource(R.string.transactions_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = expanded || filter.activeCount > 0,
                onClick = onToggle,
                label = {
                    Text(
                        if (filter.activeCount > 0) stringResource(R.string.filters_count, filter.activeCount) else stringResource(R.string.filters),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            PeriodPreset.entries.forEach { preset ->
                FilterChip(
                    selected = filter.period == preset,
                    onClick = { onFilterChange { it.copy(period = preset) } },
                    label = { Text(stringResource(preset.title)) },
                )
            }
        }
        if (filter.period == PeriodPreset.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(stringResource(R.string.date_from), filter.from, { value -> onFilterChange { it.copy(from = value) } }, Modifier.weight(1f))
                DateField(stringResource(R.string.date_to), filter.to, { value -> onFilterChange { it.copy(to = value) } }, Modifier.weight(1f))
            }
        }
        if (expanded) {
            val kinds = listOf(
                all, Option(BalanceCalculator.EXPENSE, stringResource(R.string.kind_expense)),
                Option(BalanceCalculator.INCOME, stringResource(R.string.kind_income)),
                Option(BalanceCalculator.TRANSFER, stringResource(R.string.kind_transfer)),
            )
            SelectField(
                stringResource(R.string.filter_kind), kinds.firstOrNull { it.id == filter.kind.orEmpty() }, kinds, Option::label,
                { option -> onFilterChange { it.copy(kind = option.id.ifEmpty { null }) } },
            )
            val categories = listOf(all) + state.categories
            SelectField(
                stringResource(R.string.field_category), categories.firstOrNull { it.id == filter.categoryId.orEmpty() }, categories, Option::label,
                { option -> onFilterChange { it.copy(categoryId = option.id.ifEmpty { null }) } },
            )
            val accounts = listOf(all) + state.accounts
            SelectField(
                stringResource(R.string.field_account), accounts.firstOrNull { it.id == filter.accountId.orEmpty() }, accounts, Option::label,
                { option -> onFilterChange { it.copy(accountId = option.id.ifEmpty { null }) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(
                    filter.amountMin, { value -> onFilterChange { it.copy(amountMin = value) } },
                    stringResource(R.string.filter_amount_from), state.currency, Modifier.weight(1f),
                )
                MoneyField(
                    filter.amountMax, { value -> onFilterChange { it.copy(amountMax = value) } },
                    stringResource(R.string.filter_amount_to), state.currency, Modifier.weight(1f),
                )
            }
        }
    }
}

/** Строка операции; смахивание влево запрашивает подтверждение удаления. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(row: TransactionRow, onClick: () -> Unit, onSwiped: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onSwiped()
            false // строка возвращается на место: удаление выполняется только после подтверждения
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = toneColor(Tone.NEGATIVE))
            }
        },
    ) {
        Surface { TransactionRowView(row, Modifier.clickable(onClick = onClick)) }
    }
}

@Composable
fun TransactionRowView(row: TransactionRow, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val tone = when (row.kind) {
        BalanceCalculator.INCOME -> Tone.POSITIVE
        BalanceCalculator.EXPENSE -> Tone.NEGATIVE
        else -> Tone.NEUTRAL
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CategoryIcon(row.icon, parseColor(row.color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (row.autoCategorized) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.AutoAwesome, contentDescription = stringResource(R.string.transaction_auto_category),
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (row.subtitle.isNotBlank()) {
                Text(
                    row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(row.amount, style = MaterialTheme.typography.bodyLarge, color = toneColor(tone), textAlign = TextAlign.End, maxLines = 1)
        trailing()
    }
}

/** Выбор счета, на который импортируются операции выписки. */
@Composable
private fun ImportDialog(accounts: List<Option>, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var selected by remember { mutableStateOf(accounts.firstOrNull()) }
    FormDialog(
        title = stringResource(R.string.import_statement),
        confirmLabel = stringResource(R.string.import_action),
        onConfirm = { selected?.let { onConfirm(it.id) } },
        onDismiss = onCancel,
        confirmEnabled = selected != null,
    ) {
        Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodyMedium)
        SelectField(stringResource(R.string.field_account), selected, accounts, Option::label, { selected = it })
    }
}
