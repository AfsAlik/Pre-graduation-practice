package ru.finhelper.app.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.CategoryEntity
import ru.finhelper.app.data.local.RecurringPaymentEntity
import ru.finhelper.app.data.local.TemplateEntity
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.FinanceSnapshot
import ru.finhelper.app.data.repo.ValidationError
import ru.finhelper.app.di.AppMessages
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.domain.CategoryTree
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Счет с рассчитанным балансом в валюте счета и в базовой валюте. */
data class AccountRow(val account: AccountEntity, val balance: BigDecimal, val balanceBase: BigDecimal)

data class AccountsUiState(
    val loading: Boolean = true,
    val rows: List<AccountRow> = emptyList(),
    val total: BigDecimal = BigDecimal.ZERO,
    val baseCurrency: String = "RUB",
    val currencies: List<String> = listOf("RUB"),
    /** Дата актуальности курсов валют; null – используются курсы по умолчанию. */
    val ratesAsOf: String? = null,
)

data class CategoriesUiState(val expense: List<CategoryEntity> = emptyList(), val income: List<CategoryEntity> = emptyList())

/** Шаблоны и регулярные платежи вместе со справочниками для их форм. */
data class TemplatesUiState(
    val templates: List<TemplateEntity> = emptyList(),
    val payments: List<RecurringPaymentEntity> = emptyList(),
    val accounts: List<Option> = emptyList(),
    val expenseCategories: List<Option> = emptyList(),
    val incomeCategories: List<Option> = emptyList(),
    val categoryNames: Map<String, String> = emptyMap(),
    val currency: String = "RUB",
    val today: LocalDate = LocalDate.now(),
)

/** Удаленная транзакция в корзине и число дней до окончательного удаления. */
data class TrashRow(val row: TransactionRow, val daysLeft: Int)

fun buildAccountsState(snapshot: FinanceSnapshot): AccountsUiState {
    val rows = snapshot.accounts.map { account ->
        val balance = snapshot.balances[account.id] ?: BigDecimal.ZERO
        AccountRow(account, balance, snapshot.converter.convert(balance, account.currency, snapshot.baseCurrency))
    }
    return AccountsUiState(
        loading = !snapshot.loaded,
        rows = rows,
        total = BalanceCalculator.totalBalance(snapshot.accounts, snapshot.balances, snapshot.converter, snapshot.baseCurrency),
        baseCurrency = snapshot.baseCurrency,
        currencies = snapshot.converter.currencies,
        ratesAsOf = snapshot.converter.asOf,
    )
}

fun buildTemplatesState(
    snapshot: FinanceSnapshot, templates: List<TemplateEntity>, payments: List<RecurringPaymentEntity>, today: LocalDate,
) = TemplatesUiState(
    templates = templates,
    payments = payments,
    accounts = accountOptions(snapshot.accounts),
    expenseCategories = categoryOptions(snapshot, BalanceCalculator.EXPENSE),
    incomeCategories = categoryOptions(snapshot, BalanceCalculator.INCOME),
    categoryNames = snapshot.categories.associate { it.id to CategoryTree.displayName(snapshot.categoryMap, it.id) },
    currency = snapshot.baseCurrency,
    today = today,
)

/**
 * Справочники и вспомогательные списки: счета, категории, шаблоны, регулярные
 * платежи и корзина. Нарушения правил ввода сообщаются пользователю текстом.
 */
class ReferenceViewModel(private val finance: FinanceRepository, private val messages: AppMessages) : ViewModel() {
    private val today get() = Dates.toLocalDate(System.currentTimeMillis())

    val accounts: StateFlow<AccountsUiState> = finance.snapshot.map(::buildAccountsState)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    val categories: StateFlow<CategoriesUiState> = finance.snapshot.map { snapshot ->
        CategoriesUiState(
            expense = CategoryTree.ordered(snapshot.categories, BalanceCalculator.EXPENSE),
            income = CategoryTree.ordered(snapshot.categories, BalanceCalculator.INCOME),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    val templates: StateFlow<TemplatesUiState> = combine(finance.snapshot, finance.templates, finance.recurringPayments) { snapshot, templates, payments ->
        buildTemplatesState(snapshot, templates, payments, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplatesUiState())

    val trash: StateFlow<List<TrashRow>> = combine(finance.snapshot, finance.trash()) { snapshot, deleted ->
        deleted.map { item ->
            val deletedOn = Dates.toLocalDate(item.deletedAt ?: 0)
            val daysLeft = FinanceRepository.TRASH_DAYS - ChronoUnit.DAYS.between(deletedOn, today)
            TrashRow(transactionRow(item, snapshot), daysLeft.toInt().coerceAtLeast(0))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Выполняет действие; при успехе показывает сообщение, при нарушении правила – его текст. */
    private fun run(success: Int, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                messages.show(success)
            } catch (error: ValidationError) {
                messages.show(error.message.orEmpty())
            }
        }
    }

    fun saveAccount(id: String?, name: String, type: String, currency: String, initialBalance: BigDecimal, archived: Boolean) =
        run(R.string.accounts_saved) { finance.saveAccount(id, name, type, currency, initialBalance, archived) }

    fun deleteAccount(id: String) = run(R.string.accounts_deleted) { finance.deleteAccount(id) }

    fun saveCategory(id: String?, name: String, kind: String, parentId: String?) =
        run(R.string.categories_saved) { finance.saveCategory(id, name, kind, parentId) }

    fun deleteCategory(id: String) = run(R.string.categories_deleted) { finance.deleteCategory(id) }

    fun saveTemplate(id: String?, name: String, kind: String, amount: BigDecimal, accountId: String?, categoryId: String?, comment: String) =
        run(R.string.templates_saved) { finance.saveTemplate(id, name, kind, amount, accountId, categoryId, comment) }

    fun deleteTemplate(id: String) = run(R.string.templates_deleted) { finance.deleteTemplate(id) }

    fun savePayment(
        id: String?, name: String, amount: BigDecimal, accountId: String?, categoryId: String?,
        period: String, nextDate: LocalDate, remindDays: Int,
    ) = run(R.string.recurring_saved) {
        finance.saveRecurringPayment(id, name, amount, accountId, categoryId, period, nextDate, remindDays)
    }

    fun deletePayment(id: String) = run(R.string.recurring_deleted) { finance.deleteRecurringPayment(id) }

    fun markPaid(id: String) = run(R.string.recurring_paid_done) { finance.payRecurringPayment(id) }

    fun restore(id: String) = run(R.string.transaction_restored) { finance.restoreTransaction(id) }
}
