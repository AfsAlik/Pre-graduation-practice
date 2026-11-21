package ru.finhelper.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.sync.SyncEngine
import java.io.IOException
import java.math.BigDecimal

/**
 * Проверка очереди несинхронизированных операций и синхронизации с имитацией
 * сервера (п. 4.2.1.6 д, е ТЗ): применение, повтор, конфликт, отклонение,
 * отсутствие дублирования после обрыва связи.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private val env = TestEnvironment()
    private val accounts = env.db.accounts()
    private val time = 1_800_000_000_000L

    @After
    fun tearDown() = env.close()

    private fun account(id: String, name: String, updatedAt: Long = time) =
        AccountEntity(id, name, "bank", "RUB", BigDecimal("100.00"), createdAt = time, updatedAt = updatedAt)

    private fun accountData(name: String) = JsonObject(
        mapOf(
            "name" to JsonPrimitive(name), "type" to JsonPrimitive("bank"), "currency" to JsonPrimitive("RUB"),
            "initial_balance" to JsonPrimitive("100.00"), "is_archived" to JsonPrimitive(false),
        )
    )

    private suspend fun pending(): Int = env.queue.pendingCount.first()

    /** Операция применяется сервером и удаляется из очереди. */
    @Test
    fun operationIsAppliedAndRemovedFromQueue() = runBlocking {
        env.queue.save(env.adapters.account, account("a1", "Карта"))
        assertEquals(1, pending())
        assertEquals(0, accounts.byId("a1")!!.rev)

        val outcome = env.engine.sync()

        assertEquals(1, outcome.pushed)
        assertTrue(outcome.problems.isEmpty())
        assertEquals(0, pending())
        val request = env.server.requests.single()
        assertEquals(0, request.sinceRev)
        assertEquals("account", request.changes.single().entity)
        assertEquals(JsonPrimitive("100.00"), request.changes.single().data["initial_balance"])
        assertNotNull(env.server.row("account", "a1"))
        // Запись подтверждена сервером: получен номер ревизии, он же запомнен как последний известный
        assertEquals(1, accounts.byId("a1")!!.rev)
        assertEquals(1, env.settings.sinceRev)
    }

    /** Изменения с других устройств загружаются начиная с известной ревизии. */
    @Test
    fun remoteChangesArePulledSinceKnownRevision() = runBlocking {
        env.server.remoteChange("account", "r1", accountData("Вклад"), time)
        assertEquals(1, env.engine.sync().pulled)
        assertEquals("Вклад", accounts.byId("r1")!!.name)

        env.server.remoteChange("account", "r2", accountData("Наличные"), time + 1)
        env.server.remoteChange("account", "r1", accountData("Вклад"), time + 2, deleted = true)
        val outcome = env.engine.sync()

        assertEquals(1, env.server.requests.last().sinceRev)
        assertEquals(2, outcome.pulled)
        assertEquals("Наличные", accounts.byId("r2")!!.name)
        // Удаление на сервере приходит как отметка об удалении
        assertNotNull(accounts.byId("r1")!!.deletedAt)
        assertEquals(listOf("r2"), accounts.observeActive().first().map { it.id })
    }

    /** При недоступности сервера операции остаются в очереди. */
    @Test
    fun operationsStayInQueueWhenServerIsUnavailable() = runBlocking {
        env.queue.save(env.adapters.account, account("a1", "Карта"))
        env.server.unavailable = true
        try {
            env.engine.sync()
            fail("Ожидалась ошибка сети")
        } catch (_: IOException) {
        }
        assertEquals(1, pending())
        assertEquals(0, env.settings.sinceRev)

        env.server.unavailable = false
        assertEquals(1, env.engine.sync().pushed)
        assertEquals(0, pending())
    }

    /** Повторная отправка после обрыва связи не создает дубликатов. */
    @Test
    fun resendAfterLostResponseCreatesNoDuplicates() = runBlocking {
        env.queue.save(env.adapters.account, account("a1", "Карта"))
        env.server.lostResponses = 1
        try {
            env.engine.sync()
            fail("Ожидалась ошибка сети")
        } catch (_: IOException) {
        }
        // Сервер операцию применил, но устройство об этом не узнало – операция осталась в очереди
        assertEquals(1, pending())
        assertEquals(1, env.server.rows.size)

        val outcome = env.engine.sync()

        val first = env.server.requests[0].changes.single()
        val second = env.server.requests[1].changes.single()
        assertEquals("Повтор должен идти с тем же op_id", first.opId, second.opId)
        assertEquals(0, outcome.pushed)
        assertTrue(outcome.problems.isEmpty())
        assertEquals(0, pending())
        assertEquals(1, env.server.rows.size)
        assertEquals(1, accounts.observeActive().first().size)
        assertEquals(1, accounts.byId("a1")!!.rev)
    }

    /** При конфликте побеждает более поздняя версия сервера. */
    @Test
    fun serverVersionWinsOnConflict() = runBlocking {
        env.queue.save(env.adapters.account, account("a1", "Карта"))
        env.engine.sync()
        // Другое устройство изменило запись позже, чем это устройство (до загрузки изменений)
        env.server.remoteChange("account", "a1", accountData("Карта (изменено на планшете)"), time + 10_000)
        env.queue.save(env.adapters.account, account("a1", "Карта (изменено на телефоне)", updatedAt = time + 5_000))

        val outcome = env.engine.sync()

        val problem = outcome.problems.single()
        assertEquals(SyncEngine.CONFLICT, problem.status)
        assertEquals("account", problem.entity)
        assertEquals("a1", problem.entityId)
        assertEquals(0, outcome.pushed)
        assertEquals(0, pending())
        assertEquals("Карта (изменено на планшете)", accounts.byId("a1")!!.name)
        assertEquals(1, env.server.rows.size)
    }

    /** При конфликте версия сервера загружается даже если она уже была получена ранее. */
    @Test
    fun serverVersionIsLoadedOnConflictEvenIfAlreadyPulled() = runBlocking {
        // Запись сервера помечена временем, которое опережает часы устройства
        env.server.remoteChange("account", "a1", accountData("Серверная версия"), time + 60_000)
        env.engine.sync()
        env.queue.save(env.adapters.account, account("a1", "Локальная правка", updatedAt = time))

        val outcome = env.engine.sync()

        assertEquals(SyncEngine.CONFLICT, outcome.problems.single().status)
        // Запись не вошла в обычную выдачу изменений, поэтому выполнена полная сверка (ревизия 0)
        assertEquals(0, env.server.requests.last().sinceRev)
        assertEquals("Серверная версия", accounts.byId("a1")!!.name)
        assertEquals(0, pending())
    }

    /** Отклоненная сервером новая запись удаляется с устройства. */
    @Test
    fun rejectedNewRecordIsRemovedFromDevice() = runBlocking {
        env.queue.save(env.adapters.account, account("ok", "Карта"))
        env.queue.save(env.adapters.account, account("bad", "Дубль"))
        env.server.rejections["bad"] = "Лимит для этой категории и периода уже установлен"

        val outcome = env.engine.sync()

        assertEquals(1, outcome.pushed)
        val problem = outcome.problems.single()
        assertEquals(SyncEngine.REJECTED, problem.status)
        assertEquals("Лимит для этой категории и периода уже установлен", problem.message)
        assertEquals(0, pending())
        assertNull(accounts.byId("bad"))
        assertNotNull(accounts.byId("ok"))
        assertNull(env.server.row("account", "bad"))
    }

    /** Отклоненное изменение заменяется версией сервера. */
    @Test
    fun rejectedChangeIsReplacedByServerVersion() = runBlocking {
        env.queue.save(env.adapters.account, account("a1", "Карта"))
        env.engine.sync()
        env.server.rejections["a1"] = "Некорректные данные"
        env.queue.save(env.adapters.account, account("a1", "", updatedAt = time + 1_000))

        val outcome = env.engine.sync()

        assertEquals(SyncEngine.REJECTED, outcome.problems.single().status)
        assertEquals("Карта", accounts.byId("a1")!!.name)
    }

    /** Несколько правок одной записи отправляются одной операцией. */
    @Test
    fun severalEditsOfOneRecordAreSentAsOneOperation() = runBlocking {
        env.queue.save(env.adapters.account, account("a1", "Карта"))
        env.queue.save(env.adapters.account, account("a1", "Карта Сбербанк", updatedAt = time + 1))
        env.queue.save(env.adapters.account, account("a2", "Наличные"))
        assertEquals(2, pending())

        env.engine.sync()

        assertEquals(2, env.server.requests.single().changes.size)
        assertEquals(JsonPrimitive("Карта Сбербанк"), env.server.row("account", "a1")!!.data["name"])
    }

    /** Удаление передается серверу отметкой. */
    @Test
    fun deletionIsSentAsTombstone() = runBlocking {
        env.queue.save(env.adapters.account, account("a1", "Карта"))
        env.engine.sync()
        env.queue.delete(env.adapters.account, account("a1", "Карта", updatedAt = time + 1_000).copy(deletedAt = time + 1_000))

        val outcome = env.engine.sync()

        assertEquals(1, outcome.pushed)
        assertTrue(env.server.requests.last().changes.single().deleted)
        assertNotNull(env.server.row("account", "a1")!!.deletedAt)
        assertTrue(accounts.observeActive().first().isEmpty())
    }

    /** Операции отправляются в порядке зависимостей сущностей. */
    @Test
    fun operationsAreSentInDependencyOrder() = runBlocking {
        val goal = ru.finhelper.app.data.local.GoalEntity("g1", "Отпуск", BigDecimal("1000.00"), "RUB", "2030-01-01", createdAt = time, updatedAt = time)
        env.queue.save(env.adapters.goal, goal)
        env.queue.save(env.adapters.account, account("a1", "Карта"))

        env.engine.sync()

        assertEquals(listOf("account", "goal"), env.server.requests.single().changes.map { it.entity })
    }

    /** Полная сверка не удаляет системные категории и записи с неотправленными операциями. */
    @Test
    fun fullResyncKeepsSystemCategoriesAndPendingRecords() = runBlocking {
        env.db.categories().upsertAll(SystemCategories.all())
        env.queue.save(env.adapters.account, account("bad", "Дубль"))
        env.server.rejections["bad"] = "Отклонено"
        env.engine.sync()
        assertEquals(39, env.db.categories().count())
        assertFalse(env.server.requests.isEmpty())
    }
}
