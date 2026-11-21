package ru.finhelper.app.data

import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.finhelper.app.DemoData
import ru.finhelper.app.data.local.BudgetEntity
import ru.finhelper.app.data.local.CachedDocumentEntity
import ru.finhelper.app.data.local.NotificationEntity
import ru.finhelper.app.data.local.PendingOperationEntity
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.local.TransactionEntity

/** Проверка запросов к локальной базе данных Room (база создается в памяти). */
@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private val env = TestEnvironment()
    private val db = env.db

    @After
    fun tearDown() = env.close()

    private fun transaction(id: String, occurredAt: Long, deletedAt: Long? = null, account: String = "a", to: String? = null) = TransactionEntity(
        id = id, kind = if (to == null) "expense" else "transfer", amount = BigDecimal("1234.56"), accountId = account,
        toAccountId = to, occurredAt = occurredAt, deletedAt = deletedAt,
    )

    /** Суммы и даты сохраняются без потери точности. */
    @Test
    fun amountsAndDatesAreStoredWithoutPrecisionLoss() = runBlocking {
        db.accounts().upsertAll(DemoData.accounts)
        db.transactions().upsertAll(DemoData.transactions)
        val stored = db.transactions().byId(DemoData.transactions.first().id)!!
        assertEquals(DemoData.transactions.first(), stored)
        assertEquals(BigDecimal("-18400.00"), db.accounts().byId(DemoData.CREDIT)!!.initialBalance)
        assertEquals(DemoData.transactions.size, db.transactions().observeActive().first().size)
    }

    /** Активные операции упорядочены по убыванию даты а удаленные попадают в корзину. */
    @Test
    fun activeTransactionsAreSortedAndDeletedGoToTrash() = runBlocking {
        db.transactions().upsertAll(listOf(transaction("old", 100), transaction("new", 300), transaction("gone", 200, deletedAt = 5_000)))
        assertEquals(listOf("new", "old"), db.transactions().observeActive().first().map { it.id })
        assertEquals(listOf("gone"), db.transactions().observeDeleted(since = 1_000).first().map { it.id })
        assertTrue(db.transactions().observeDeleted(since = 6_000).first().isEmpty())

        db.transactions().purgeDeletedBefore(6_000)
        assertNull(db.transactions().byId("gone"))
        assertNotNull(db.transactions().byId("old"))
    }

    /** Операции счета учитывают входящие переводы. */
    @Test
    fun accountTransactionsIncludeIncomingTransfers() = runBlocking {
        db.transactions().upsertAll(listOf(transaction("1", 1, account = "a"), transaction("2", 2, account = "b", to = "a"), transaction("3", 3, account = "c")))
        assertEquals(2, db.transactions().countForAccount("a"))
        assertEquals(1, db.transactions().countForAccount("c"))
        assertEquals(0, db.transactions().countForAccount("x"))
    }

    /** Обновление записи заменяет ее по идентификатору. */
    @Test
    fun upsertReplacesRecordById() = runBlocking {
        val budget = BudgetEntity("b1", "cat", "month", BigDecimal("5000.00"))
        db.budgets().upsert(budget)
        db.budgets().upsert(budget.copy(limitAmount = BigDecimal("7000.00")))
        assertEquals(1, db.budgets().observeActive().first().size)
        assertEquals(BigDecimal("7000.00"), db.budgets().find("cat", "month")!!.limitAmount)
        assertNull(db.budgets().find("cat", "week"))
        db.budgets().upsert(budget.copy(deletedAt = 1))
        assertNull(db.budgets().find("cat", "month"))
    }

    /** Справочник категорий хранит системные категории отдельно от пользовательских. */
    @Test
    fun systemCategoriesAreKeptApartFromUserCategories() = runBlocking {
        db.categories().upsertAll(SystemCategories.all())
        db.categories().upsert(SystemCategories.all().first().copy(id = "own", name = "Хобби", isSystem = false))
        assertEquals(40, db.categories().count())
        assertEquals(listOf("own"), db.categories().allIds())
        // Системные категории выводятся первыми
        assertEquals("Хобби", db.categories().observeActive().first().last().name)
    }

    /** Очередь операций сохраняет порядок и заменяет операции по записи. */
    @Test
    fun operationQueueKeepsOrderAndReplacesByEntity() = runBlocking {
        val operations = db.pendingOperations()
        fun operation(opId: String, entityId: String) = PendingOperationEntity(opId = opId, entity = "account", entityId = entityId, updatedAt = 1, deleted = false, payload = "{}")
        operations.insert(operation("op1", "a1"))
        operations.insert(operation("op2", "a2"))
        operations.insert(operation("op3", "a3"))
        assertEquals(3, operations.observeCount().first())
        assertEquals(listOf("op1", "op2"), operations.oldest(2).map { it.opId })

        operations.deleteForEntity("account", "a2")
        operations.deleteByOpIds(listOf("op1", "нет такой"))
        assertEquals(listOf("op3"), operations.all().map { it.opId })
    }

    /** Сохраненные ответы сервера и уведомления. */
    @Test
    fun cachedDocumentsAndNotifications() = runBlocking {
        db.cachedDocuments().put(CachedDocumentEntity("rates", "{}", 1))
        db.cachedDocuments().put(CachedDocumentEntity("rates", """{"rates":{}}""", 2))
        assertEquals(2, db.cachedDocuments().get("rates")!!.savedAt)
        assertNull(db.cachedDocuments().get("portfolio"))

        db.notifications().upsert(NotificationEntity("n1", "risk", "Риск", "Текст", createdAt = 1))
        db.notifications().upsert(NotificationEntity("n2", "risk", "Риск", "Текст", createdAt = 2, read = true))
        assertEquals(1, db.notifications().observeUnreadCount().first())
        db.notifications().markAllRead()
        assertEquals(0, db.notifications().observeUnreadCount().first())
        assertEquals(listOf("n2", "n1"), db.notifications().observeAll().first().map { it.id })
    }
}
