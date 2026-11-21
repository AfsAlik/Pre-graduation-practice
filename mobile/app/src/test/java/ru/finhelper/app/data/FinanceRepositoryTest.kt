package ru.finhelper.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.remote.ProfileDto
import ru.finhelper.app.data.repo.CacheStore
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.TransactionDraft
import ru.finhelper.app.data.repo.ValidationError
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Проверка бизнес-правил, которые приложение применяет при вводе данных без
 * сети (те же правила сервер применяет при синхронизации).
 */
@RunWith(RobolectricTestRunner::class)
class FinanceRepositoryTest {
    private val env = TestEnvironment()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val day = 24 * 60 * 60 * 1000L
    private var now = Dates.parseInstant("2026-11-20T12:00:00Z")
    private var changes = 0
    private val repository = FinanceRepository(
        db = env.db, adapters = env.adapters, queue = env.queue, cache = CacheStore(env.db.cachedDocuments()),
        profile = MutableStateFlow(ProfileDto()), scope = scope, now = { now }, onChanged = { changes++ },
    )
    private val groceries = SystemCategories.id("Продукты")
    private val salary = SystemCategories.id("Зарплата")

    @Before
    fun setUp() = runBlocking {
        Dates.zone = ZoneOffset.UTC
        env.db.categories().upsertAll(SystemCategories.all())
    }

    @After
    fun tearDown() {
        scope.cancel()
        env.close()
    }

    /** Проверяет, что действие отклонено с указанным сообщением. */
    private suspend fun assertRejected(message: String, action: suspend () -> Unit) {
        try {
            action()
            fail("Ожидалось сообщение: $message")
        } catch (error: ValidationError) {
            assertEquals(message, error.message)
        }
    }

    private suspend fun pending(): Int = env.queue.pendingCount.first()

    private fun expense(accountId: String, amount: String, categoryId: String? = groceries) =
        TransactionDraft(kind = "expense", amount = BigDecimal(amount), accountId = accountId, categoryId = categoryId, occurredAt = now)

    /** Транзакция сохраняется в базе и ставится в очередь синхронизации. */
    @Test
    fun transactionIsStoredAndQueuedForSync() = runBlocking {
        val card = repository.saveAccount(null, "Карта", "bank", "USD", BigDecimal("100.00"))
        val saved = repository.saveTransaction(expense(card.id, "25.5").copy(merchant = " Магазин "))

        assertEquals("USD", saved.currency)
        assertEquals(BigDecimal("25.50"), saved.amount)
        assertEquals("Магазин", saved.merchant)
        assertEquals(saved, env.db.transactions().byId(saved.id))
        assertEquals(2, pending())
        assertEquals(2, changes)
        val operation = env.db.pendingOperations().all().last()
        assertEquals("transaction", operation.entity)
        assertEquals(saved.id, operation.entityId)
        assertTrue(operation.payload.contains("\"amount\":\"25.50\""))
    }

    /** Проверка обязательных полей транзакции. */
    @Test
    fun transactionRequiredFieldsAreValidated() = runBlocking {
        val card = repository.saveAccount(null, "Карта", "bank", "RUB", BigDecimal.ZERO)
        assertRejected("Сумма должна быть больше нуля") { repository.saveTransaction(expense(card.id, "0")) }
        assertRejected("Выберите счет") { repository.saveTransaction(expense("нет", "10")) }
        assertRejected("Выберите категорию") { repository.saveTransaction(expense(card.id, "10", categoryId = null)) }
        assertRejected("Вид категории не соответствует виду операции") { repository.saveTransaction(expense(card.id, "10", categoryId = salary)) }
        assertEquals(1, pending())
    }

    /** Перевод между счетами в разных валютах пересчитывается по курсу. */
    @Test
    fun transferBetweenCurrenciesIsConvertedByRate() = runBlocking {
        val rub = repository.saveAccount(null, "Карта", "bank", "RUB", BigDecimal.ZERO)
        val usd = repository.saveAccount(null, "Доллары", "bank", "USD", BigDecimal.ZERO)
        val draft = TransactionDraft(kind = "transfer", amount = BigDecimal("9000"), accountId = rub.id, toAccountId = usd.id, categoryId = groceries, occurredAt = now)

        val transfer = repository.saveTransaction(draft)

        // Курс по умолчанию: 90 рублей за доллар
        assertEquals(BigDecimal("100.00"), transfer.amountTo)
        assertNull(transfer.categoryId)
        assertRejected("Счет списания и счет зачисления должны различаться") { repository.saveTransaction(draft.copy(toAccountId = rub.id)) }
        assertRejected("Выберите счет зачисления") { repository.saveTransaction(draft.copy(toAccountId = null)) }
    }

    /** Удаленная транзакция хранится в корзине 30 дней и восстанавливается. */
    @Test
    fun deletedTransactionStaysInTrashFor30DaysAndCanBeRestored() = runBlocking {
        val card = repository.saveAccount(null, "Карта", "bank", "RUB", BigDecimal.ZERO)
        val saved = repository.saveTransaction(expense(card.id, "500"))

        repository.deleteTransaction(saved.id)
        assertTrue(env.db.transactions().observeActive().first().isEmpty())
        assertEquals(listOf(saved.id), repository.trash().first().map { it.id })
        assertTrue(env.db.pendingOperations().all().last().deleted)

        repository.restoreTransaction(saved.id)
        assertEquals(saved.amount, env.db.transactions().observeActive().first().single().amount)
        assertFalse(env.db.pendingOperations().all().last().deleted)

        repository.deleteTransaction(saved.id)
        now += 31 * day
        repository.purgeTrash()
        assertNull(env.db.transactions().byId(saved.id))
    }

    /** Счет с операциями нельзя удалить. */
    @Test
    fun accountWithTransactionsCannotBeDeleted() = runBlocking {
        val card = repository.saveAccount(null, "Карта", "bank", "RUB", BigDecimal.ZERO)
        val empty = repository.saveAccount(null, "Копилка", "cash", "RUB", BigDecimal.ZERO)
        repository.saveTransaction(expense(card.id, "500"))
        assertRejected("По счету есть операции. Переведите счет в архив") { repository.deleteAccount(card.id) }
        repository.deleteAccount(empty.id)
        assertEquals(listOf("Карта"), env.db.accounts().observeActive().first().map { it.name })
        assertRejected("Введите название счета") { repository.saveAccount(null, " ", "cash", "RUB", BigDecimal.ZERO) }
    }

    /** Системную категорию нельзя изменить а подкатегория наследует вид. */
    @Test
    fun systemCategoryIsReadOnlyAndSubcategoryKeepsKind() = runBlocking {
        assertRejected("Системную категорию нельзя изменить") { repository.saveCategory(groceries, "Еда", "expense", null) }
        assertRejected("Системную категорию нельзя удалить") { repository.deleteCategory(groceries) }
        assertRejected("Подкатегория должна иметь тот же вид, что и категория") { repository.saveCategory(null, "Премия", "income", groceries) }
        repository.saveCategory(null, "Фермерские продукты", "expense", groceries)
        val created = env.db.categories().observeActive().first().first { it.name == "Фермерские продукты" }
        assertEquals(groceries, created.parentId)
        assertFalse(created.isSystem)
        assertEquals("shopping_cart", created.icon)
    }

    /** Лимит устанавливается один на категорию и период. */
    @Test
    fun budgetLimitIsUniquePerCategoryAndPeriod() = runBlocking {
        repository.setBudget(groceries, "month", BigDecimal("20000"))
        repository.setBudget(groceries, "month", BigDecimal("25000"))
        repository.setBudget(groceries, "week", BigDecimal("6000"))

        val budgets = env.db.budgets().observeActive().first()
        assertEquals(2, budgets.size)
        assertEquals(BigDecimal("25000"), budgets.first { it.period == "month" }.limitAmount)
        assertRejected("Бюджетный лимит устанавливается для категории расходов") { repository.setBudget(salary, "month", BigDecimal("1")) }
        assertRejected("Лимит должен быть больше нуля") { repository.setBudget(groceries, "year", BigDecimal.ZERO) }
        // Две правки одного лимита объединены в одну операцию очереди
        assertEquals(2, pending())
    }

    /** Достигнутая цель переводится в режим просмотра. */
    @Test
    fun achievedGoalBecomesReadOnly() = runBlocking {
        val today = LocalDate.of(2026, 11, 20)
        assertRejected("Срок достижения цели должен быть в будущем") { repository.saveGoal(null, "Отпуск", BigDecimal("1000"), today, "high") }
        repository.saveGoal(null, "Отпуск", BigDecimal("1000"), today.plusMonths(6), "high")
        val goal = env.db.goals().observeActive().first().single()

        assertFalse(repository.addContribution(goal.id, BigDecimal("400")))
        assertTrue(repository.addContribution(goal.id, BigDecimal("600")))

        val achieved = env.db.goals().byId(goal.id)!!
        assertEquals("achieved", achieved.status)
        assertEquals(now, achieved.achievedAt)
        val message = "Достигнутая цель доступна только для просмотра"
        assertRejected(message) { repository.addContribution(goal.id, BigDecimal("1")) }
        assertRejected(message) { repository.saveGoal(goal.id, "Отпуск 2", BigDecimal("5000"), today.plusMonths(6), "low") }
        assertRejected(message) { repository.deleteGoal(goal.id) }
        assertEquals(2, env.db.goalContributions().forGoal(goal.id).size)
    }

    /** Количество и цену сделки нельзя изменить через 7 дней. */
    @Test
    fun tradeQuantityAndPriceAreLockedAfter7Days() = runBlocking {
        repository.saveTrade(null, "stock", "sber", "Сбербанк", "buy", BigDecimal("10"), BigDecimal("250.5"), BigDecimal("3"), "RUB", now)
        val trade = env.db.trades().observeActive().first().single()
        assertEquals("SBER", trade.ticker)

        now += 6 * day
        repository.saveTrade(trade.id, "stock", "SBER", "Сбербанк", "buy", BigDecimal("12"), BigDecimal("250.5"), BigDecimal("3"), "RUB", trade.tradedAt)
        assertEquals(0, BigDecimal("12").compareTo(env.db.trades().byId(trade.id)!!.quantity))

        now += 2 * day
        assertRejected(FinanceRepository.LOCKED_MESSAGE) {
            repository.saveTrade(trade.id, "stock", "SBER", "Сбербанк", "buy", BigDecimal("15"), BigDecimal("250.5"), BigDecimal("3"), "RUB", trade.tradedAt)
        }
        // Остальные поля сделки изменить можно
        repository.saveTrade(trade.id, "stock", "SBER", "ПАО Сбербанк", "buy", BigDecimal("12"), BigDecimal("250.5"), BigDecimal("3"), "RUB", trade.tradedAt)
        assertEquals("ПАО Сбербанк", env.db.trades().byId(trade.id)!!.name)
        assertRejected("Сделку нельзя удалить спустя 7 дней после фиксации позиции. Оформите корректировку новой сделкой.") {
            repository.deleteTrade(trade.id)
        }
    }

    /** Продать можно не больше чем есть в позиции. */
    @Test
    fun cannotSellMoreThanPosition() = runBlocking {
        repository.saveTrade(null, "stock", "GAZP", "Газпром", "buy", BigDecimal("10"), BigDecimal("120"), BigDecimal.ZERO, "RUB", now)
        assertRejected("Количество в сделке продажи превышает размер позиции") {
            repository.saveTrade(null, "stock", "GAZP", "Газпром", "sell", BigDecimal("11"), BigDecimal("130"), BigDecimal.ZERO, "RUB", now)
        }
        repository.saveTrade(null, "stock", "GAZP", "Газпром", "sell", BigDecimal("10"), BigDecimal("130"), BigDecimal.ZERO, "RUB", now)
        assertEquals(2, env.db.trades().observeActive().first().size)
        assertRejected("Введите тикер актива") {
            repository.saveTrade(null, "stock", " ", "", "buy", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "RUB", now)
        }
    }

    /** Отметка регулярного платежа создает расход и переносит дату. */
    @Test
    fun payingRecurringPaymentCreatesExpenseAndMovesDate() = runBlocking {
        val card = repository.saveAccount(null, "Карта", "bank", "RUB", BigDecimal.ZERO)
        val rent = SystemCategories.id("Жилье/Аренда")
        repository.saveRecurringPayment(null, "Аренда", BigDecimal("35000"), card.id, rent, "month", LocalDate.of(2026, 12, 1), 3)
        val payment = env.db.recurringPayments().observeActive().first().single()

        repository.payRecurringPayment(payment.id)

        val expense = env.db.transactions().observeActive().first().single()
        assertEquals("recurring", expense.source)
        assertEquals(rent, expense.categoryId)
        assertEquals(0, BigDecimal("35000").compareTo(expense.amount))
        assertEquals("2027-01-01", env.db.recurringPayments().byId(payment.id)!!.nextDate)
        assertNotNull(env.db.pendingOperations().all().firstOrNull { it.entity == "recurring_payment" })
    }
}
