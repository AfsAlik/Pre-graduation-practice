package ru.finhelper.app.data.sync

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.finhelper.app.data.local.AppDatabase
import ru.finhelper.app.data.local.PendingOperationEntity
import ru.finhelper.app.data.local.SyncedEntity
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.remote.AppJson
import ru.finhelper.app.data.remote.SyncChangeDto
import ru.finhelper.app.data.remote.SyncRequest
import ru.finhelper.app.data.remote.SyncResponse
import ru.finhelper.app.data.remote.SyncResultDto

/** Передача запроса синхронизации серверу (POST /sync). В тестах заменяется имитацией сервера. */
fun interface SyncTransport {
    suspend fun send(request: SyncRequest): SyncResponse
}

/** Операция, не принятая сервером: конфликт с более поздним изменением либо нарушение бизнес-правила. */
data class SyncProblem(val entity: String, val entityId: String, val status: String, val message: String)

/** Итог сеанса синхронизации. */
data class SyncOutcome(
    /** Число операций, принятых сервером. */
    val pushed: Int = 0,
    /** Число записей, полученных от сервера. */
    val pulled: Int = 0,
    val problems: List<SyncProblem> = emptyList(),
)

/**
 * Синхронизация локальной базы данных с сервером (п. 4.2.1.6 г–е ТЗ).
 *
 * Алгоритм сеанса:
 *  1. Операции очереди отправляются серверу вместе с номером последней известной
 *     ревизии. Операция удаляется из очереди только после ответа сервера о ней,
 *     поэтому при обрыве связи она будет отправлена повторно с тем же op_id, а
 *     сервер распознает повтор (статус duplicate) – дублирования не возникает.
 *  2. Изменения, полученные от сервера, записываются в базу. Запись, по которой в
 *     очереди осталась неотправленная операция, не перезаписывается.
 *  3. При статусе conflict или rejected действует версия сервера: если ее нет среди
 *     полученных изменений, выполняется полная сверка – загрузка всех записей
 *     пользователя и удаление локальных записей, которых нет на сервере.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val adapters: SyncAdapters,
    private val settings: SettingsStore,
    private val transport: SyncTransport,
) {
    private val mutex = Mutex()
    private val operations = db.pendingOperations()

    /**
     * Выполняет сеанс синхронизации. Одновременно выполняется не более одного сеанса.
     * @throws Exception при сетевой ошибке; очередь при этом не изменяется
     */
    suspend fun sync(): SyncOutcome = mutex.withLock {
        var outcome = SyncOutcome()
        // Большая очередь отправляется частями; число запросов ограничено размером очереди
        val rounds = maxOf(1, (operations.count() + BATCH_SIZE - 1) / BATCH_SIZE)
        repeat(rounds) {
            // Операции упорядочены так же, как их применяет сервер: сначала счета и категории,
            // затем записи, которые на них ссылаются
            val batch = operations.all().sortedBy { entityRank(it.entity) }.take(BATCH_SIZE)
            val response = transport.send(
                SyncRequest(deviceId = settings.deviceId, sinceRev = settings.sinceRev, changes = batch.map(::toChange))
            )
            val step = apply(batch, response)
            outcome = SyncOutcome(outcome.pushed + step.pushed, outcome.pulled + step.pulled, outcome.problems + step.problems)
        }
        outcome
    }

    private fun entityRank(entity: String): Int =
        adapters.all.indexOfFirst { it.entity == entity }.let { if (it < 0) adapters.all.size else it }

    private fun toChange(operation: PendingOperationEntity) = SyncChangeDto(
        opId = operation.opId,
        entity = operation.entity,
        id = operation.entityId,
        updatedAt = operation.updatedAt,
        deleted = operation.deleted,
        data = AppJson.parseToJsonElement(operation.payload).jsonObject,
    )

    private suspend fun apply(sent: List<PendingOperationEntity>, response: SyncResponse): SyncOutcome {
        val problems = response.results.filter { it.status == CONFLICT || it.status == REJECTED }
        var pulled = 0
        var needFullResync = false
        db.withTransaction {
            operations.deleteByOpIds(response.results.map { it.opId })
            // Записи с операциями, поставленными в очередь уже после отправки запроса
            val stillPending = operations.all().map { it.entity to it.entityId }.toSet()
            val received = mutableSetOf<Pair<String, String>>()
            for (adapter in adapters.all) {
                val items = response.changes[adapter.entity].orEmpty()
                pulled += upsertFromServer(adapter, items, stillPending, received)
            }
            // Версия сервера должна заменить локальную для отклоненных операций и для повторов,
            // исход первой отправки которых неизвестен
            needFullResync = response.results.any { result ->
                result.status != APPLIED && (result.entity to result.id) !in received
            }
            settings.sinceRev = response.rev
        }
        if (needFullResync) pulled += fullResync()
        return SyncOutcome(
            pushed = response.results.count { it.status == APPLIED },
            pulled = pulled,
            problems = problems.map { it.toProblem(sent) },
        )
    }

    private suspend fun <T : SyncedEntity> upsertFromServer(
        adapter: SyncAdapter<T>,
        items: List<JsonObject>,
        pending: Set<Pair<String, String>>,
        received: MutableSet<Pair<String, String>>,
    ): Int {
        val accepted = items.filter { json ->
            val key = adapter.entity to json.getValue("id").jsonPrimitive.content
            received += key
            key !in pending
        }
        adapter.upsertAll(accepted.map(adapter::decode))
        return accepted.size
    }

    /**
     * Полная сверка с сервером: загружает все записи пользователя (ревизия 0) и
     * удаляет локальные записи, которых на сервере нет. Записи с неотправленными
     * операциями не затрагиваются.
     */
    private suspend fun fullResync(): Int {
        val response = transport.send(SyncRequest(deviceId = settings.deviceId, sinceRev = 0))
        var pulled = 0
        db.withTransaction {
            val pending = operations.all().map { it.entity to it.entityId }.toSet()
            for (adapter in adapters.all) {
                val items = response.changes[adapter.entity].orEmpty()
                val received = mutableSetOf<Pair<String, String>>()
                pulled += upsertFromServer(adapter, items, pending, received)
                val serverIds = received.map { it.second }.toSet()
                val orphans = adapter.allIds().filter { it !in serverIds && (adapter.entity to it) !in pending }
                orphans.chunked(SQL_CHUNK).forEach { adapter.deleteByIds(it) }
            }
            settings.sinceRev = response.rev
        }
        return pulled
    }

    private fun SyncResultDto.toProblem(sent: List<PendingOperationEntity>): SyncProblem {
        val operation = sent.firstOrNull { it.opId == opId }
        return SyncProblem(
            entity = entity.ifEmpty { operation?.entity.orEmpty() },
            entityId = id.ifEmpty { operation?.entityId.orEmpty() },
            status = status,
            message = message,
        )
    }

    companion object {
        const val APPLIED = "applied"
        const val DUPLICATE = "duplicate"
        const val CONFLICT = "conflict"
        const val REJECTED = "rejected"

        /** Наибольшее число операций в одном запросе (сервер принимает не более 2000). */
        const val BATCH_SIZE = 500
        private const val SQL_CHUNK = 500
    }
}
