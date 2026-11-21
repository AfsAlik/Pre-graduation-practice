package ru.finhelper.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.AppDatabase
import ru.finhelper.app.data.prefs.SecretCipher
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.remote.SyncChangeDto
import ru.finhelper.app.data.remote.SyncRequest
import ru.finhelper.app.data.remote.SyncResponse
import ru.finhelper.app.data.remote.SyncResultDto
import ru.finhelper.app.data.sync.SyncAdapters
import ru.finhelper.app.data.sync.SyncEngine
import ru.finhelper.app.data.sync.SyncQueue
import ru.finhelper.app.data.sync.SyncTransport
import java.io.IOException

/** Шифрование-заглушка для тестов: хранилище ключей Android в среде JVM недоступно. */
object PlainCipher : SecretCipher {
    override fun encrypt(plain: String): String = plain
    override fun decrypt(encoded: String): String = encoded
}

/** Тестовое окружение: незашифрованная база данных Room в памяти и настройки Robolectric. */
class TestEnvironment {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val settings = SettingsStore(context.getSharedPreferences("test-settings", Context.MODE_PRIVATE), "http://localhost/")
    val adapters = SyncAdapters(db)
    val queue = SyncQueue(db)
    val server = FakeSyncServer()
    val engine = SyncEngine(db, adapters, settings, server)

    fun close() = db.close()
}

/**
 * Имитация серверной части протокола синхронизации (POST /sync). Повторяет
 * правила сервера: распознавание повторных операций по op_id, отклонение
 * операции при более позднем изменении на сервере (conflict) и при нарушении
 * бизнес-правила (rejected), выдача изменений с номером ревизии больше известного.
 */
class FakeSyncServer : SyncTransport {
    class Row(val data: JsonObject, val updatedAt: Long, val rev: Long, val deletedAt: Long?)

    val rows = linkedMapOf<Pair<String, String>, Row>()
    val requests = mutableListOf<SyncRequest>()
    private val processedOperations = mutableSetOf<String>()
    private var rev = 0L

    /** Идентификаторы записей, которые сервер отклоняет, и текст причины. */
    val rejections = mutableMapOf<String, String>()

    /** Число ближайших запросов, ответ на которые «теряется» после обработки (обрыв связи). */
    var lostResponses = 0

    /** Сервер недоступен: запрос не обрабатывается. */
    var unavailable = false

    override suspend fun send(request: SyncRequest): SyncResponse {
        if (unavailable) throw IOException("Сервер недоступен")
        requests += request
        val results = request.changes.map(::apply)
        if (lostResponses > 0) {
            lostResponses--
            throw IOException("Ответ сервера не получен")
        }
        val changes = rows.entries.filter { it.value.rev > request.sinceRev }
            .groupBy({ it.key.first }, { json(it.key.second, it.value) })
        return SyncResponse(rev, results, changes)
    }

    private fun apply(change: SyncChangeDto): SyncResultDto {
        fun result(status: String, message: String = "") = SyncResultDto(change.opId, change.entity, change.id, status, message)
        if (!processedOperations.add(change.opId)) return result("duplicate")
        val key = change.entity to change.id
        val existing = rows[key]
        if (existing != null && existing.updatedAt > change.updatedAt) return result("conflict", "Запись изменена на другом устройстве позже")
        rejections[change.id]?.let { return result("rejected", it) }
        if (change.deleted) {
            if (existing != null) rows[key] = Row(existing.data, change.updatedAt, ++rev, change.updatedAt)
        } else {
            rows[key] = Row(change.data, change.updatedAt, ++rev, null)
        }
        return result("applied")
    }

    /** Изменение записи с другого устройства. */
    fun remoteChange(entity: String, id: String, data: JsonObject, updatedAt: Long, deleted: Boolean = false) {
        rows[entity to id] = Row(data, updatedAt, ++rev, if (deleted) updatedAt else null)
    }

    fun row(entity: String, id: String): Row? = rows[entity to id]

    private fun json(id: String, row: Row): JsonObject = JsonObject(
        row.data + mapOf(
            "id" to JsonPrimitive(id),
            "created_at" to JsonPrimitive(Dates.isoInstant(row.updatedAt)),
            "updated_at" to JsonPrimitive(Dates.isoInstant(row.updatedAt)),
            "deleted_at" to (row.deletedAt?.let { JsonPrimitive(Dates.isoInstant(it)) } ?: JsonNull),
            "rev" to JsonPrimitive(row.rev),
        )
    )
}
