package ru.finhelper.app.ui.transactions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.local.TemplateEntity
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.TextRequest
import ru.finhelper.app.data.remote.apiCall
import ru.finhelper.app.data.repo.ErrorLogger
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.FinanceSnapshot
import ru.finhelper.app.data.repo.ReceiptRecognizer
import ru.finhelper.app.data.repo.TransactionDraft
import ru.finhelper.app.data.repo.ValidationError
import ru.finhelper.app.di.AppMessages
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.domain.CategorySuggester
import ru.finhelper.app.domain.ReceiptParser
import ru.finhelper.app.domain.VoiceParser

/** Состояние формы «Добавить транзакцию» / «Изменить транзакцию». */
data class TransactionFormState(
    val loading: Boolean = true,
    /** Идентификатор изменяемой транзакции; null – добавление. */
    val id: String? = null,
    val kind: String = BalanceCalculator.EXPENSE,
    val amount: String = "",
    val accountId: String? = null,
    val toAccountId: String? = null,
    val amountTo: String = "",
    val categoryId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val merchant: String = "",
    val comment: String = "",
    /** Способ ввода: manual, template, receipt или voice. */
    val source: String = "manual",
    val accounts: List<Option> = emptyList(),
    val categories: List<Option> = emptyList(),
    val templates: List<TemplateEntity> = emptyList(),
    /** Валюта суммы – валюта выбранного счета. */
    val currency: String = "RUB",
    /** Валюта счета зачисления, если она отличается от валюты счета списания. */
    val toCurrency: String? = null,
    /** Категория подставлена автоматически, а не выбрана пользователем. */
    val categorySuggested: Boolean = false,
    val amountError: String? = null,
    val accountError: String? = null,
    val toAccountError: String? = null,
    val categoryError: String? = null,
    /** Выполняется распознавание чека. */
    val recognizing: Boolean = false,
    val saving: Boolean = false,
)

/**
 * Форма транзакции: ручной ввод, шаблон, фотография чека и голосовой ввод
 * (п. 4.2.1.1 а–г ТЗ). Разбор текста чека и голосовой фразы, а также подбор
 * категории по истории операций выполняются на устройстве; при наличии сети
 * категория уточняется моделью серверной части.
 */
class TransactionEditViewModel(
    private val transactionId: String?,
    private val finance: FinanceRepository,
    private val api: FinHelperApi,
    private val recognizer: ReceiptRecognizer,
    private val messages: AppMessages,
    private val errorLogger: ErrorLogger,
) : ViewModel() {
    private val formState = MutableStateFlow(TransactionFormState(id = transactionId))
    val state: StateFlow<TransactionFormState> = formState

    private val snapshot: FinanceSnapshot get() = finance.snapshot.value

    /** Категория выбрана пользователем вручную – автоматический подбор ее не заменяет. */
    private var categoryChosenManually = false

    init {
        viewModelScope.launch {
            val data = finance.snapshot.filter { it.loaded }.first()
            val existing = transactionId?.let { finance.transaction(it) }
            val base = if (existing != null) {
                categoryChosenManually = existing.categoryId != null
                TransactionFormState(
                    id = existing.id, kind = existing.kind, amount = Money.toInput(existing.amount),
                    accountId = existing.accountId, toAccountId = existing.toAccountId,
                    amountTo = Money.toInput(existing.amountTo), categoryId = existing.categoryId,
                    date = Dates.toLocalDate(existing.occurredAt), merchant = existing.merchant, comment = existing.comment,
                    source = existing.source,
                )
            } else {
                TransactionFormState(
                    accountId = data.accounts.firstOrNull { !it.isArchived }?.id,
                    date = Dates.toLocalDate(System.currentTimeMillis()),
                )
            }
            formState.value = withReferences(base.copy(loading = false))
        }
        viewModelScope.launch { finance.templates.collect { list -> formState.update { it.copy(templates = list) } } }
    }

    /** Дополняет состояние списками счетов и категорий и валютами выбранных счетов. */
    private fun withReferences(state: TransactionFormState): TransactionFormState {
        val data = snapshot
        val account = data.accountMap[state.accountId]
        val target = data.accountMap[state.toAccountId]
        return state.copy(
            accounts = accountOptions(data.accounts),
            categories = if (state.kind == BalanceCalculator.TRANSFER) emptyList() else categoryOptions(data, state.kind),
            currency = account?.currency ?: data.baseCurrency,
            toCurrency = target?.currency?.takeIf { state.kind == BalanceCalculator.TRANSFER && it != account?.currency },
        )
    }

    fun update(transform: (TransactionFormState) -> TransactionFormState) = formState.update { withReferences(transform(it)) }

    fun setKind(kind: String) = update {
        if (it.kind == kind) it else it.copy(kind = kind, categoryId = null, categorySuggested = false, categoryError = null)
    }.also { categoryChosenManually = false }

    fun setCategory(id: String) {
        categoryChosenManually = true
        update { it.copy(categoryId = id, categorySuggested = false, categoryError = null) }
    }

    /** Изменение описания: категория подбирается по ранее выполненным операциям. */
    fun setMerchant(text: String) {
        update { it.copy(merchant = text) }
        suggestCategory(text, online = false)
    }

    private fun suggestCategory(text: String, online: Boolean) {
        val state = formState.value
        if (categoryChosenManually || state.kind == BalanceCalculator.TRANSFER || text.isBlank()) return
        val local = CategorySuggester.suggest(text, state.kind, snapshot.transactions, snapshot.categories)
        if (local != null) {
            update { it.copy(categoryId = local, categorySuggested = true, categoryError = null) }
        } else if (online) {
            viewModelScope.launch {
                val suggestion = apiCall { api.suggestCategory(TextRequest(text), state.kind) }.getOrNull()?.categoryId
                if (suggestion != null && !categoryChosenManually && snapshot.categoryMap.containsKey(suggestion)) {
                    update { it.copy(categoryId = suggestion, categorySuggested = true, categoryError = null) }
                }
            }
        }
    }

    /** Заполняет форму по шаблону. */
    fun applyTemplate(template: TemplateEntity) {
        categoryChosenManually = template.categoryId != null
        update {
            it.copy(
                kind = template.kind,
                amount = Money.toInput(template.amount),
                accountId = template.accountId ?: it.accountId,
                categoryId = template.categoryId,
                comment = template.comment,
                merchant = template.name,
                source = "template",
                amountError = null, categoryError = null, categorySuggested = false,
            )
        }
    }

    /** Заполняет форму по распознанной голосовой фразе. */
    fun applyVoice(phrase: String) {
        val parsed = VoiceParser.parse(phrase)
        categoryChosenManually = false
        update {
            it.copy(
                kind = parsed.kind,
                amount = parsed.amount?.let(Money::toInput) ?: it.amount,
                merchant = parsed.description,
                categoryId = if (it.kind == parsed.kind) it.categoryId else null,
                source = "voice",
                amountError = null,
            )
        }
        suggestCategory(parsed.description, online = true)
        if (parsed.amount != null) messages.show(R.string.transaction_voice_done, phrase) else messages.show(R.string.transaction_voice_failed)
    }

    /** Распознает фотографию чека и заполняет сумму, дату, описание и категорию. */
    fun recognizeReceipt(image: Uri) {
        formState.update { it.copy(recognizing = true) }
        viewModelScope.launch {
            val text = runCatching { recognizer.recognize(image) }.onFailure { errorLogger.log(it, "receipt") }.getOrNull()
            if (text.isNullOrBlank()) {
                formState.update { it.copy(recognizing = false) }
                messages.show(R.string.transaction_receipt_failed)
                return@launch
            }
            applyReceiptText(text)
        }
    }

    private suspend fun applyReceiptText(text: String) {
        val parsed = ReceiptParser.parse(text)
        categoryChosenManually = false
        update {
            it.copy(
                kind = BalanceCalculator.EXPENSE,
                amount = parsed.amount?.let(Money::toInput) ?: it.amount,
                date = parsed.occurredAt?.let(Dates::toLocalDate) ?: it.date,
                merchant = parsed.merchant.ifBlank { it.merchant },
                source = "receipt",
                amountError = null,
            )
        }
        suggestCategory(parsed.merchant, online = false)
        // При наличии сети результат уточняется серверным разбором текста чека
        val remote = apiCall { api.parseReceipt(text.toRequestBody("text/plain".toMediaType())) }.getOrNull()
        if (remote != null) {
            update { current ->
                val category = remote.category.categoryId?.takeIf { !categoryChosenManually && current.categoryId == null && it in snapshot.categoryMap }
                current.copy(
                    amount = current.amount.ifBlank { remote.amount?.let(Money::toInput).orEmpty() },
                    merchant = current.merchant.ifBlank { remote.merchant },
                    categoryId = category ?: current.categoryId,
                    categorySuggested = current.categorySuggested || category != null,
                )
            }
        }
        formState.update { it.copy(recognizing = false) }
        messages.show(if (formState.value.amount.isNotBlank()) R.string.transaction_receipt_done else R.string.transaction_receipt_failed)
    }

    /** Проверяет поля и сохраняет транзакцию; ошибки выводятся рядом с полями (п. 4.4.5.20 ТЗ). */
    fun save(defaultAccountName: String, onDone: () -> Unit) {
        val state = formState.value
        val amount = Money.parse(state.amount)?.takeIf { it.signum() > 0 }
        val isTransfer = state.kind == BalanceCalculator.TRANSFER
        val amountError = if (amount == null) "Введите сумму больше нуля, например 350,00" else null
        val accountError = if (state.accountId == null && snapshot.accounts.isNotEmpty()) "Выберите счет" else null
        val toAccountError = when {
            !isTransfer -> null
            state.toAccountId == null -> "Выберите счет зачисления"
            state.toAccountId == state.accountId -> "Счет зачисления должен отличаться от счета списания"
            else -> null
        }
        val categoryError = if (!isTransfer && state.categoryId == null) "Выберите категорию из списка" else null
        if (amount == null || accountError != null || toAccountError != null || categoryError != null) {
            formState.update {
                it.copy(amountError = amountError, accountError = accountError, toAccountError = toAccountError, categoryError = categoryError)
            }
            return
        }
        formState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                // Первая операция нового пользователя: счет «Наличные» создается автоматически
                val accountId = state.accountId
                    ?: finance.saveAccount(null, defaultAccountName, "cash", snapshot.baseCurrency, BigDecimal.ZERO).id
                val sameDay = state.id?.let { finance.transaction(it) }?.takeIf { Dates.toLocalDate(it.occurredAt) == state.date }
                finance.saveTransaction(
                    TransactionDraft(
                        id = state.id, kind = state.kind, amount = amount, accountId = accountId,
                        toAccountId = state.toAccountId.takeIf { isTransfer },
                        amountTo = Money.parse(state.amountTo).takeIf { isTransfer && state.toCurrency != null },
                        categoryId = state.categoryId.takeIf { !isTransfer },
                        // Время сохраняется, если дата не менялась; для сегодняшней даты – текущий момент
                        occurredAt = sameDay?.occurredAt ?: occurredAt(state.date),
                        comment = state.comment, merchant = state.merchant, source = state.source,
                    )
                )
                messages.show(if (state.id == null) R.string.transaction_added else R.string.transaction_saved)
                onDone()
            } catch (error: ValidationError) {
                messages.show(error.message.orEmpty())
                formState.update { it.copy(saving = false) }
            }
        }
    }

    private fun occurredAt(date: LocalDate): Long {
        val now = System.currentTimeMillis()
        return if (date == Dates.toLocalDate(now)) now else Dates.noon(date)
    }

    fun delete(onDone: () -> Unit) {
        val id = formState.value.id ?: return
        viewModelScope.launch {
            finance.deleteTransaction(id)
            messages.show(R.string.transaction_deleted)
            onDone()
        }
    }
}
