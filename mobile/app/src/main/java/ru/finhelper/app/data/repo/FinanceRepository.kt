package ru.finhelper.app.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Ids
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.AppDatabase
import ru.finhelper.app.data.local.BudgetEntity
import ru.finhelper.app.data.local.CategoryEntity
import ru.finhelper.app.data.local.GoalContributionEntity
import ru.finhelper.app.data.local.GoalEntity
import ru.finhelper.app.data.local.RecurringPaymentEntity
import ru.finhelper.app.data.local.TemplateEntity
import ru.finhelper.app.data.local.TradeEntity
import ru.finhelper.app.data.local.TransactionEntity
import ru.finhelper.app.data.remote.PortfolioDto
import ru.finhelper.app.data.remote.ProfileDto
import ru.finhelper.app.data.remote.RatesDto
import ru.finhelper.app.data.sync.SyncAdapters
import ru.finhelper.app.data.sync.SyncQueue
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.domain.CurrencyConverter
import ru.finhelper.app.domain.GoalCalculator
import ru.finhelper.app.domain.Periods
import ru.finhelper.app.domain.PortfolioCalculator
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Нарушение правила ввода данных; текст показывается пользователю рядом с формой. */
class ValidationError(message: String) : Exception(message)

/** Данные формы транзакции. */
data class TransactionDraft(
    val id: String? = null,
    val kind: String,
    val amount: BigDecimal,
    val accountId: String,
    val toAccountId: String? = null,
    val amountTo: BigDecimal? = null,
    val categoryId: String? = null,
    val occurredAt: Long,
    val comment: String = "",
    val merchant: String = "",
    val source: String = "manual",
)

/**
 * Учетные данные пользователя: счета, категории, транзакции, шаблоны, регулярные
 * платежи, бюджеты, цели и сделки.
 *
 * Каждое изменение сразу записывается в локальную базу и ставится в очередь
 * синхронизации, поэтому все функции учета работают без сети (п. 4.1.3.1 ТЗ).
 * Перед сохранением проверяются те же бизнес-правила, что и на сервере
 * (services/entities.py), чтобы операция не была отклонена при синхронизации.
 *
 * @param onChanged вызывается после каждого изменения: запуск синхронизации и
 * проверка условий локальных уведомлений
 */
class FinanceRepository(
    private val db: AppDatabase,
    private val adapters: SyncAdapters,
    private val queue: SyncQueue,
    cache: CacheStore,
    profile: StateFlow<ProfileDto>,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val onChanged: () -> Unit = {},
) {
    /** Курсы валют, сохраненные при последнем обращении к серверу. */
    val converter: Flow<CurrencyConverter> = cache.observe(CacheStore.KEY_RATES, RatesDto.serializer()).map { cached ->
        val rates = cached?.value?.rates.orEmpty()
            .mapNotNull { (code, rate) -> rate.toBigDecimalOrNull()?.let { code to it } }.toMap()
        if (rates.isEmpty()) CurrencyConverter.FALLBACK else CurrencyConverter(rates, cached?.value?.asOf)
    }

    /** Срез всех учетных данных; обновляется при любом изменении локальной базы. */
    val snapshot: StateFlow<FinanceSnapshot> = combine<Any?, FinanceSnapshot>(
        db.accounts().observeActive(), db.categories().observeActive(), db.transactions().observeActive(),
        db.budgets().observeActive(), db.goals().observeActive(), db.goalContributions().observeActive(),
        db.trades().observeActive(), cache.observe(CacheStore.KEY_PORTFOLIO, PortfolioDto.serializer()),
        converter, profile,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val portfolio = values[7] as Cached<PortfolioDto>?
        @Suppress("UNCHECKED_CAST")
        FinanceSnapshot(
            accounts = values[0] as List<AccountEntity>,
            categories = values[1] as List<CategoryEntity>,
            transactions = values[2] as List<TransactionEntity>,
            budgets = values[3] as List<BudgetEntity>,
            goals = values[4] as List<GoalEntity>,
            contributions = values[5] as List<GoalContributionEntity>,
            trades = values[6] as List<TradeEntity>,
            prices = portfolio?.value?.positions.orEmpty()
                .mapNotNull { position -> position.currentPrice?.let { position.ticker.uppercase() to it } }.toMap(),
            pricesAt = portfolio?.savedAt ?: 0,
            converter = values[8] as CurrencyConverter,
            profile = values[9] as ProfileDto,
            loaded = true,
        )
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, FinanceSnapshot())

    val templates: Flow<List<TemplateEntity>> = db.templates().observeActive()
    val recurringPayments: Flow<List<RecurringPaymentEntity>> = db.recurringPayments().observeActive()

    /** Удаленные транзакции, доступные для восстановления в течение 30 дней. */
    fun trash(): Flow<List<TransactionEntity>> = db.transactions().observeDeleted(now() - TRASH_DAYS * DAY_MILLIS)

    // ------------------------------------------------------------------ счета
    suspend fun saveAccount(
        id: String?, name: String, type: String, currency: String, initialBalance: BigDecimal, isArchived: Boolean = false,
    ): AccountEntity {
        if (name.isBlank()) throw ValidationError("Введите название счета")
        val time = now()
        val existing = id?.let { db.accounts().byId(it) }
        val account = existing?.copy(
            name = name.trim(), type = type, currency = currency, initialBalance = initialBalance,
            isArchived = isArchived, updatedAt = time, deletedAt = null,
        ) ?: AccountEntity(Ids.random(), name.trim(), type, currency, initialBalance, isArchived, createdAt = time, updatedAt = time)
        queue.save(adapters.account, account)
        onChanged()
        return account
    }

    suspend fun deleteAccount(id: String) {
        val account = db.accounts().byId(id) ?: return
        if (db.transactions().countForAccount(id) > 0) throw ValidationError("По счету есть операции. Переведите счет в архив")
        queue.delete(adapters.account, account.copy(deletedAt = now(), updatedAt = now()))
        onChanged()
    }

    // ------------------------------------------------------------------ категории
    suspend fun saveCategory(id: String?, name: String, kind: String, parentId: String?) {
        if (name.isBlank()) throw ValidationError("Введите название категории")
        val existing = id?.let { db.categories().byId(it) }
        if (existing?.isSystem == true) throw ValidationError("Системную категорию нельзя изменить")
        val parent = parentId?.let { db.categories().byId(it) }
        if (parent != null && parent.kind != kind) throw ValidationError("Подкатегория должна иметь тот же вид, что и категория")
        if (parent != null && parent.id == id) throw ValidationError("Категория не может быть вложена сама в себя")
        val time = now()
        val category = existing?.copy(name = name.trim(), kind = kind, parentId = parentId, updatedAt = time, deletedAt = null)
            ?: CategoryEntity(
                Ids.random(), name.trim(), kind, parentId,
                icon = parent?.icon.orEmpty(), color = parent?.color.orEmpty(), createdAt = time, updatedAt = time,
            )
        queue.save(adapters.category, category)
        onChanged()
    }

    suspend fun deleteCategory(id: String) {
        val category = db.categories().byId(id) ?: return
        if (category.isSystem) throw ValidationError("Системную категорию нельзя удалить")
        queue.delete(adapters.category, category.copy(deletedAt = now(), updatedAt = now()))
        onChanged()
    }

    // ------------------------------------------------------------------ транзакции
    /** Сохраняет доход, расход или перевод (п. 4.2.1.1 а ТЗ). */
    suspend fun saveTransaction(draft: TransactionDraft): TransactionEntity {
        if (draft.amount.signum() <= 0) throw ValidationError("Сумма должна быть больше нуля")
        val account = db.accounts().byId(draft.accountId)?.takeIf { it.deletedAt == null } ?: throw ValidationError("Выберите счет")
        val time = now()
        val existing = draft.id?.let { db.transactions().byId(it) }
        var item = (existing ?: TransactionEntity(
            id = Ids.random(), kind = draft.kind, amount = draft.amount, accountId = account.id,
            occurredAt = draft.occurredAt, source = draft.source, createdAt = time,
        )).copy(
            kind = draft.kind, amount = draft.amount.setScale(2, RoundingMode.HALF_UP), currency = account.currency,
            accountId = account.id, occurredAt = draft.occurredAt, comment = draft.comment.trim(),
            merchant = draft.merchant.trim(), updatedAt = time, deletedAt = null,
        )
        if (draft.kind == BalanceCalculator.TRANSFER) {
            val target = draft.toAccountId?.let { db.accounts().byId(it) }?.takeIf { it.deletedAt == null }
                ?: throw ValidationError("Выберите счет зачисления")
            if (target.id == account.id) throw ValidationError("Счет списания и счет зачисления должны различаться")
            val amountTo = when {
                target.currency == account.currency -> item.amount
                draft.amountTo != null && draft.amountTo.signum() > 0 -> draft.amountTo.setScale(2, RoundingMode.HALF_UP)
                else -> snapshot.value.converter.convert(item.amount, account.currency, target.currency)
            }
            item = item.copy(toAccountId = target.id, amountTo = amountTo, categoryId = null)
        } else {
            val category = draft.categoryId?.let { db.categories().byId(it) }?.takeIf { it.deletedAt == null }
                ?: throw ValidationError("Выберите категорию")
            if (category.kind != draft.kind) throw ValidationError("Вид категории не соответствует виду операции")
            item = item.copy(
                toAccountId = null, amountTo = null, categoryId = category.id,
                autoCategorized = existing?.autoCategorized == true && existing.categoryId == category.id,
            )
        }
        queue.save(adapters.transaction, item)
        onChanged()
        return item
    }

    /** Удаляет транзакцию: она перемещается в корзину и хранится 30 дней (п. 4.2.1.1 и ТЗ). */
    suspend fun deleteTransaction(id: String) {
        val item = db.transactions().byId(id) ?: return
        queue.delete(adapters.transaction, item.copy(deletedAt = now(), updatedAt = now()))
        onChanged()
    }

    /** Восстанавливает удаленную транзакцию со всеми атрибутами. */
    suspend fun restoreTransaction(id: String) {
        val item = db.transactions().byId(id) ?: return
        queue.save(adapters.transaction, item.copy(deletedAt = null, updatedAt = now()))
        onChanged()
    }

    suspend fun transaction(id: String): TransactionEntity? = db.transactions().byId(id)

    /** Окончательно удаляет с устройства транзакции, срок хранения которых в корзине истек. */
    suspend fun purgeTrash() = db.transactions().purgeDeletedBefore(now() - TRASH_DAYS * DAY_MILLIS)

    // ------------------------------------------------------------------ шаблоны и регулярные платежи
    suspend fun saveTemplate(id: String?, name: String, kind: String, amount: BigDecimal, accountId: String?, categoryId: String?, comment: String) {
        if (name.isBlank()) throw ValidationError("Введите название шаблона")
        val time = now()
        val currency = accountId?.let { db.accounts().byId(it) }?.currency ?: snapshot.value.baseCurrency
        val existing = id?.let { db.templates().byId(it) }
        val template = existing?.copy(
            name = name.trim(), kind = kind, amount = amount, currency = currency, accountId = accountId,
            categoryId = categoryId, comment = comment.trim(), updatedAt = time, deletedAt = null,
        ) ?: TemplateEntity(Ids.random(), name.trim(), kind, amount, currency, accountId, categoryId, comment.trim(), createdAt = time, updatedAt = time)
        queue.save(adapters.template, template)
        onChanged()
    }

    suspend fun deleteTemplate(id: String) {
        val template = db.templates().byId(id) ?: return
        queue.delete(adapters.template, template.copy(deletedAt = now(), updatedAt = now()))
        onChanged()
    }

    suspend fun saveRecurringPayment(
        id: String?, name: String, amount: BigDecimal, accountId: String?, categoryId: String?,
        period: String, nextDate: LocalDate, remindDaysBefore: Int, isActive: Boolean = true,
    ) {
        if (name.isBlank()) throw ValidationError("Введите название платежа")
        if (amount.signum() <= 0) throw ValidationError("Сумма должна быть больше нуля")
        val time = now()
        val currency = accountId?.let { db.accounts().byId(it) }?.currency ?: snapshot.value.baseCurrency
        val existing = id?.let { db.recurringPayments().byId(it) }
        val payment = existing?.copy(
            name = name.trim(), amount = amount, currency = currency, accountId = accountId, categoryId = categoryId,
            period = period, nextDate = nextDate.toString(), remindDaysBefore = remindDaysBefore.coerceIn(0, 30),
            isActive = isActive, updatedAt = time, deletedAt = null,
        ) ?: RecurringPaymentEntity(
            Ids.random(), name.trim(), amount, currency, accountId, categoryId, period, nextDate.toString(),
            remindDaysBefore.coerceIn(0, 30), isActive, createdAt = time, updatedAt = time,
        )
        queue.save(adapters.recurringPayment, payment)
        onChanged()
    }

    suspend fun deleteRecurringPayment(id: String) {
        val payment = db.recurringPayments().byId(id) ?: return
        queue.delete(adapters.recurringPayment, payment.copy(deletedAt = now(), updatedAt = now()))
        onChanged()
    }

    /**
     * Отмечает регулярный платеж выполненным: создает расход и переносит дату
     * следующего платежа на один период вперед.
     */
    suspend fun payRecurringPayment(id: String) {
        val payment = db.recurringPayments().byId(id) ?: return
        val accountId = payment.accountId ?: throw ValidationError("Укажите счет в настройках платежа")
        val categoryId = payment.categoryId ?: throw ValidationError("Укажите категорию в настройках платежа")
        saveTransaction(
            TransactionDraft(
                kind = BalanceCalculator.EXPENSE, amount = payment.amount, accountId = accountId, categoryId = categoryId,
                occurredAt = now(), merchant = payment.name, source = "recurring",
            )
        )
        val next = Periods.next(payment.period, Dates.parseDate(payment.nextDate))
        queue.save(adapters.recurringPayment, payment.copy(nextDate = next.toString(), updatedAt = now()))
        onChanged()
    }

    // ------------------------------------------------------------------ бюджет
    /**
     * Устанавливает лимит по категории расходов на период (п. 4.2.1.2 а ТЗ).
     * Если лимит для этой категории и периода уже есть, изменяется его сумма.
     */
    suspend fun setBudget(categoryId: String, period: String, limit: BigDecimal, id: String? = null) {
        if (limit.signum() <= 0) throw ValidationError("Лимит должен быть больше нуля")
        val category = db.categories().byId(categoryId) ?: throw ValidationError("Выберите категорию")
        if (category.kind != BalanceCalculator.EXPENSE) throw ValidationError("Бюджетный лимит устанавливается для категории расходов")
        val time = now()
        val existing = db.budgets().find(categoryId, period) ?: id?.let { db.budgets().byId(it) }
        val budget = existing?.copy(categoryId = categoryId, period = period, limitAmount = limit, isActive = true, updatedAt = time, deletedAt = null)
            ?: BudgetEntity(Ids.random(), categoryId, period, limit, snapshot.value.baseCurrency, createdAt = time, updatedAt = time)
        queue.save(adapters.budget, budget)
        onChanged()
    }

    suspend fun deleteBudget(id: String) {
        val budget = db.budgets().byId(id) ?: return
        queue.delete(adapters.budget, budget.copy(deletedAt = now(), updatedAt = now()))
        onChanged()
    }

    // ------------------------------------------------------------------ цели
    suspend fun saveGoal(id: String?, name: String, target: BigDecimal, deadline: LocalDate, priority: String) {
        if (name.isBlank()) throw ValidationError("Введите название цели")
        if (target.signum() <= 0) throw ValidationError("Целевая сумма должна быть больше нуля")
        val existing = id?.let { db.goals().byId(it) }
        if (existing != null && isAchieved(existing)) throw ValidationError(ACHIEVED_MESSAGE)
        if (existing == null && !deadline.isAfter(Dates.toLocalDate(now()))) throw ValidationError("Срок достижения цели должен быть в будущем")
        val time = now()
        val goal = existing?.copy(name = name.trim(), targetAmount = target, deadline = deadline.toString(), priority = priority, updatedAt = time)
            ?: GoalEntity(Ids.random(), name.trim(), target, snapshot.value.baseCurrency, deadline.toString(), priority, createdAt = time, updatedAt = time)
        queue.save(adapters.goal, goal)
        onChanged()
    }

    suspend fun deleteGoal(id: String) {
        val goal = db.goals().byId(id) ?: return
        if (isAchieved(goal)) throw ValidationError(ACHIEVED_MESSAGE)
        queue.delete(adapters.goal, goal.copy(deletedAt = now(), updatedAt = now()))
        onChanged()
    }

    /**
     * Вносит накопление по цели. При накоплении целевой суммы цель переводится в
     * режим просмотра (п. 4.2.1.2 ж ТЗ); сервер подтверждает это при синхронизации.
     * @return true, если цель достигнута этим взносом
     */
    suspend fun addContribution(goalId: String, amount: BigDecimal, comment: String = ""): Boolean {
        if (amount.signum() <= 0) throw ValidationError("Сумма должна быть больше нуля")
        val goal = db.goals().byId(goalId)?.takeIf { it.deletedAt == null } ?: throw ValidationError("Цель не найдена")
        if (isAchieved(goal)) throw ValidationError(ACHIEVED_MESSAGE)
        val time = now()
        queue.save(
            adapters.goalContribution,
            GoalContributionEntity(Ids.random(), goalId, amount, occurredAt = time, comment = comment.trim(), createdAt = time, updatedAt = time),
        )
        val achieved = GoalCalculator.saved(goalId, db.goalContributions().forGoal(goalId)) >= goal.targetAmount
        if (achieved) db.goals().upsert(goal.copy(status = GoalCalculator.STATUS_ACHIEVED, achievedAt = time))
        onChanged()
        return achieved
    }

    private suspend fun isAchieved(goal: GoalEntity): Boolean = goal.status == GoalCalculator.STATUS_ACHIEVED ||
        GoalCalculator.saved(goal.id, db.goalContributions().forGoal(goal.id)) >= goal.targetAmount

    // ------------------------------------------------------------------ инвестиции
    /**
     * Сохраняет сделку. Количество и цену нельзя изменить спустя 7 дней после
     * фиксации сделки (п. 4.2.1.3 е ТЗ); продать можно не больше, чем есть в позиции.
     */
    suspend fun saveTrade(
        id: String?, assetType: String, ticker: String, name: String, side: String,
        quantity: BigDecimal, price: BigDecimal, fee: BigDecimal, currency: String, tradedAt: Long,
    ) {
        val symbol = ticker.trim().uppercase()
        if (symbol.isEmpty()) throw ValidationError("Введите тикер актива")
        if (quantity.signum() <= 0) throw ValidationError("Количество должно быть больше нуля")
        if (price.signum() <= 0) throw ValidationError("Цена должна быть больше нуля")
        val time = now()
        val existing = id?.let { db.trades().byId(it) }
        if (existing != null && PortfolioCalculator.isLocked(existing, time) &&
            (existing.quantity.compareTo(quantity) != 0 || existing.price.compareTo(price) != 0)
        ) {
            throw ValidationError(LOCKED_MESSAGE)
        }
        if (side == "sell") {
            val others = db.trades().forTicker(symbol).filter { it.id != id }
            if (quantity > PortfolioCalculator.position(others).quantity) {
                throw ValidationError("Количество в сделке продажи превышает размер позиции")
            }
        }
        val trade = (existing ?: TradeEntity(
            id = Ids.random(), assetType = assetType, ticker = symbol, quantity = quantity, price = price,
            tradedAt = tradedAt, createdAt = time,
        )).copy(
            assetType = assetType, ticker = symbol, name = name.trim(), side = side,
            quantity = quantity.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros(),
            price = price.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros(),
            fee = fee.setScale(2, RoundingMode.HALF_UP), currency = currency, tradedAt = tradedAt, updatedAt = time, deletedAt = null,
        )
        queue.save(adapters.trade, trade)
        onChanged()
    }

    suspend fun deleteTrade(id: String) {
        val trade = db.trades().byId(id) ?: return
        if (PortfolioCalculator.isLocked(trade, now())) {
            throw ValidationError("Сделку нельзя удалить спустя 7 дней после фиксации позиции. Оформите корректировку новой сделкой.")
        }
        queue.delete(adapters.trade, trade.copy(deletedAt = now(), updatedAt = now()))
        onChanged()
    }

    companion object {
        const val TRASH_DAYS = 30L
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val ACHIEVED_MESSAGE = "Достигнутая цель доступна только для просмотра"
        const val LOCKED_MESSAGE = "Количество и цену покупки нельзя изменить спустя 7 дней после фиксации позиции. " +
            "Оформите корректировку новой сделкой."
    }
}
