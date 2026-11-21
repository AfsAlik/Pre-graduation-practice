package ru.finhelper.app.data.sync

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import ru.finhelper.app.core.Ids
import ru.finhelper.app.data.local.AppDatabase
import ru.finhelper.app.data.local.PendingOperationEntity
import ru.finhelper.app.data.local.SyncedEntity

/**
 * Очередь несинхронизированных операций (п. 4.2.1.6 д ТЗ).
 *
 * Изменение записи и постановка операции в очередь выполняются в одной транзакции
 * базы данных: введенные данные либо сохранены и будут отправлены, либо не
 * сохранены вовсе (п. 4.4.8.3, 4.4.8.4 ТЗ).
 */
class SyncQueue(private val db: AppDatabase) {
    private val operations = db.pendingOperations()

    /** Число операций, ожидающих отправки на сервер. */
    val pendingCount: Flow<Int> = operations.observeCount()

    /** Сохраняет запись и ставит в очередь операцию ее создания или изменения. */
    suspend fun <T : SyncedEntity> save(adapter: SyncAdapter<T>, item: T) = db.withTransaction {
        adapter.upsert(item)
        enqueue(adapter.entity, item.id, item.updatedAt, deleted = false, payload = adapter.payload(item).toString())
    }

    /** Сохраняет запись, помеченную удаленной, и ставит в очередь операцию удаления. */
    suspend fun <T : SyncedEntity> delete(adapter: SyncAdapter<T>, deletedItem: T) = db.withTransaction {
        adapter.upsert(deletedItem)
        enqueue(adapter.entity, deletedItem.id, deletedItem.updatedAt, deleted = true, payload = "{}")
    }

    /**
     * Добавляет операцию в очередь. Более ранняя неотправленная операция с той же
     * записью заменяется: серверу достаточно последнего состояния записи.
     */
    private suspend fun enqueue(entity: String, entityId: String, updatedAt: Long, deleted: Boolean, payload: String) {
        operations.deleteForEntity(entity, entityId)
        operations.insert(
            PendingOperationEntity(
                opId = Ids.random(), entity = entity, entityId = entityId,
                updatedAt = updatedAt, deleted = deleted, payload = payload,
            )
        )
    }
}
