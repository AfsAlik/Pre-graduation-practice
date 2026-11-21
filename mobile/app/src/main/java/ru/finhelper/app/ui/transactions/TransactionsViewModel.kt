package ru.finhelper.app.ui.transactions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.time.LocalDate
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
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.TransactionEntity
import ru.finhelper.app.data.remote.userMessage
import ru.finhelper.app.data.repo.FileRepository
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.FinanceSnapshot
import ru.finhelper.app.data.sync.SyncManager
import ru.finhelper.app.di.AppMessages
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.domain.CategoryTree

/** Готовые периоды фильтра истории операций. */
enum class PeriodPreset(val title: Int) {
    THIS_MONTH(R.string.period_this_month),
    LAST_MONTH(R.string.period_last_month),
    THREE_MONTHS(R.string.period_three_months),
    YEAR(R.string.period_this_year),
    ALL(R.string.period_all),
    CUSTOM(R.string.period_custom),
}

/** Условия отбора операций: период, вид, категория, счет, сумма и строка поиска (п. 4.2.1.1 з ТЗ). */
data class TransactionFilter(
    val period: PeriodPreset = PeriodPreset.THIS_MONTH,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val kind: String? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
    val amountMin: String = "",
    val amountMax: String = "",
    val query: String = "",
) {
    /** Число включенных дополнительных условий (для подписи кнопки «Фильтры»). */
    val activeCount: Int
        get() = listOf(kind != null, categoryId != null, accountId != null, amountMin.isNotBlank(), amountMax.isNotBlank()).count { it }

    /** Границы периода; null – без ограничения. */
    fun bounds(today: LocalDate): Pair<LocalDate?, LocalDate?> = when (period) {
        PeriodPreset.THIS_MONTH -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
        PeriodPreset.LAST_MONTH -> today.minusMonths(1).let { it.withDayOfMonth(1) to it.withDayOfMonth(it.lengthOfMonth()) }
        PeriodPreset.THREE_MONTHS -> today.minusMonths(2).withDayOfMonth(1) to today
        PeriodPreset.YEAR -> LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
        PeriodPreset.ALL -> null to null
        PeriodPreset.CUSTOM -> from to to
    }
}

/** Строка списка операций. */
data class TransactionRow(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val amount: String,
    val icon: String,
    val color: String,
    /** Категория определена автоматически. */
    val autoCategorized: Boolean = false,
)

/** Операции одного дня. */
data class DayGroup(val date: LocalDate, val rows: List<TransactionRow>)

/** Пункт выпадающего списка: идентификатор и название. */
data class Option(val id: String, val label: String)

data class TransactionsUiState(
    val loading: Boolean = true,
    val filter: TransactionFilter = TransactionFilter(),
    val groups: List<DayGroup> = emptyList(),
    val count: Int = 0,
    val income: BigDecimal = BigDecimal.ZERO,
    val expense: BigDecimal = BigDecimal.ZERO,
    val currency: String = "RUB",
    val accounts: List<Option> = emptyList(),
    val categories: List<Option> = emptyList(),
    /** Выполняется импорт или экспорт (показывается индикатор, п. 4.4.5.36 ТЗ). */
    val busy: Boolean = false,
    /** Файл выписки выбран – нужно указать счет для импорта. */
    val importFile: Uri? = null,
)

/** Преобразует операцию в строку списка. */
fun transactionRow(item: TransactionEntity, snapshot: FinanceSnapshot): TransactionRow {
    val account = snapshot.accountMap[item.accountId]
    val category = snapshot.categoryMap[item.categoryId]
    val root = CategoryTree.rootOf(snapshot.categoryMap, item.categoryId)
    val details = listOf(item.merchant, item.comment).filter { it.isNotBlank() }.joinToString(" · ")
    return when (item.kind) {
        BalanceCalculator.TRANSFER -> TransactionRow(
            id = item.id, kind = item.kind,
            title = "${account?.name.orEmpty()} → ${snapshot.accountMap[item.toAccountId]?.name.orEmpty()}",
            subtitle = details.ifBlank { "Перевод между счетами" },
            amount = Money.format(item.amount, item.currency),
            icon = "transfer", color = "#546E7A",
        )
        else -> TransactionRow(
            id = item.id, kind = item.kind,
            title = CategoryTree.displayName(snapshot.categoryMap, item.categoryId),
            subtitle = listOf(details, account?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
            amount = Money.format(if (item.kind == BalanceCalculator.INCOME) item.amount else item.amount.negate(), item.currency, signed = true),
            icon = (category ?: root)?.icon.orEmpty(), color = root?.color.orEmpty(),
            autoCategorized = item.autoCategorized,
        )
    }
}

/** Отбирает операции по фильтру и группирует их по дням. */
fun buildTransactionsState(snapshot: FinanceSnapshot, filter: TransactionFilter, today: LocalDate): TransactionsUiState {
    val (from, to) = filter.bounds(today)
    val fromMillis = from?.let(Dates::startOfDay) ?: Long.MIN_VALUE
    val toMillis = to?.let(Dates::endOfDay) ?: Long.MAX_VALUE
    val categoryIds = filter.categoryId?.let { CategoryTree.subtreeIds(snapshot.categoryMap, it) }
    val min = Money.parse(filter.amountMin)
    val max = Money.parse(filter.amountMax)
    val query = filter.query.trim()
    val items = snapshot.transactions.filter { item ->
        item.occurredAt in fromMillis..toMillis &&
            (filter.kind == null || item.kind == filter.kind) &&
            (categoryIds == null || item.categoryId in categoryIds) &&
            (filter.accountId == null || item.accountId == filter.accountId || item.toAccountId == filter.accountId) &&
            (min == null || item.amount >= min) && (max == null || item.amount <= max) &&
            (query.isEmpty() || item.comment.contains(query, true) || item.merchant.contains(query, true) ||
                CategoryTree.displayName(snapshot.categoryMap, item.categoryId).contains(query, true))
    }
    var income = BigDecimal.ZERO
    var expense = BigDecimal.ZERO
    for (item in items) {
        val amount = snapshot.converter.convert(item.amount, item.currency, snapshot.baseCurrency)
        if (item.kind == BalanceCalculator.INCOME) income += amount else if (item.kind == BalanceCalculator.EXPENSE) expense += amount
    }
    return TransactionsUiState(
        loading = !snapshot.loaded,
        filter = filter,
        groups = items.groupBy { Dates.toLocalDate(it.occurredAt) }.toSortedMap(compareByDescending { it })
            .map { (date, list) -> DayGroup(date, list.map { transactionRow(it, snapshot) }) },
        count = items.size,
        income = income,
        expense = expense,
        currency = snapshot.baseCurrency,
        accounts = snapshot.accounts.map { Option(it.id, it.name) },
        categories = categoryOptions(snapshot, null),
    )
}

/** Категории для выпадающего списка: подкатегории выводятся в виде «Категория / Подкатегория». */
fun categoryOptions(snapshot: FinanceSnapshot, kind: String?): List<Option> {
    val kinds = if (kind == null) listOf(BalanceCalculator.EXPENSE, BalanceCalculator.INCOME) else listOf(kind)
    return kinds.flatMap { CategoryTree.ordered(snapshot.categories, it) }
        .map { Option(it.id, CategoryTree.displayName(snapshot.categoryMap, it.id)) }
}

fun accountOptions(accounts: List<AccountEntity>): List<Option> =
    accounts.filter { !it.isArchived }.map { Option(it.id, "${it.name} (${Money.symbol(it.currency)})") }

/** История операций: фильтры, поиск, удаление, импорт выписки и экспорт. */
class TransactionsViewModel(
    private val finance: FinanceRepository,
    private val files: FileRepository,
    private val syncManager: SyncManager,
    private val messages: AppMessages,
) : ViewModel() {
    private val filter = MutableStateFlow(TransactionFilter())
    private val busy = MutableStateFlow(false)
    private val importFile = MutableStateFlow<Uri?>(null)

    val state: StateFlow<TransactionsUiState> = combine(finance.snapshot, filter, busy, importFile) { snapshot, current, working, file ->
        buildTransactionsState(snapshot, current, Dates.toLocalDate(System.currentTimeMillis())).copy(busy = working, importFile = file)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun updateFilter(transform: (TransactionFilter) -> TransactionFilter) = filter.update(transform)

    fun delete(id: String) {
        viewModelScope.launch {
            finance.deleteTransaction(id)
            messages.show(R.string.transaction_deleted)
        }
    }

    /** Файл выписки выбран: далее пользователь указывает счет. */
    fun chooseImportFile(uri: Uri?) {
        importFile.value = uri
    }

    /** Импорт банковской выписки (п. 4.2.1.7 а ТЗ); после импорта данные загружаются синхронизацией. */
    fun importStatement(accountId: String) {
        val uri = importFile.value ?: return
        importFile.value = null
        busy.value = true
        viewModelScope.launch {
            files.importStatement(uri, accountId).onSuccess { result ->
                syncManager.syncNow()
                messages.show(R.string.import_done, result.imported, result.skippedDuplicates)
            }.onFailure { messages.show(it.userMessage()) }
            busy.value = false
        }
    }

    /** Экспорт истории операций за период фильтра в PDF или XLSX (п. 4.2.1.7 б ТЗ). */
    fun export(target: Uri, format: String) {
        val today = Dates.toLocalDate(System.currentTimeMillis())
        val (from, to) = filter.value.bounds(today)
        busy.value = true
        viewModelScope.launch {
            files.exportTransactions(target, format, from ?: today.minusYears(5), to ?: today)
                .onSuccess { messages.show(R.string.file_saved) }
                .onFailure { messages.show(it.userMessage()) }
            busy.value = false
        }
    }
}
