package ru.finhelper.app.data

import java.math.BigDecimal
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Ids
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.local.TradeEntity
import ru.finhelper.app.data.local.TransactionEntity
import ru.finhelper.app.data.remote.ApiError
import ru.finhelper.app.data.remote.AppJson
import ru.finhelper.app.data.remote.SyncResponse
import ru.finhelper.app.data.sync.SyncAdapter

/** Проверка соответствия формата обмена программному интерфейсу сервера. */
class SerializationTest {
    private val serverTransaction = """
        {"id":"11111111-1111-1111-1111-111111111111","created_at":"2026-11-20T10:00:00Z",
         "updated_at":"2026-11-20T10:00:05.123456+00:00","deleted_at":null,"rev":42,"kind":"expense",
         "amount":"1234.50","currency":"RUB","account_id":"acc","to_account_id":null,"amount_to":null,
         "category_id":"cat","occurred_at":"2026-11-20T09:30:00Z","comment":"","merchant":"Пятерочка",
         "source":"manual","receipt_file":null,"auto_categorized":true,"category_confidence":0.93,"external_id":null}
    """.trimIndent()

    private val adapter = SyncAdapter(
        "transaction", TransactionEntity.serializer(),
        setOf("kind", "amount", "currency", "account_id", "to_account_id", "amount_to", "category_id", "occurred_at", "comment", "merchant", "source"),
        upsert = {}, upsertAll = {}, allIds = { emptyList() }, deleteByIds = {},
    )

    /** Запись сервера разбирается с неизвестными полями. */
    @Test
    fun serverRecordIsDecodedIgnoringUnknownFields() {
        val entity = adapter.decode(AppJson.parseToJsonElement(serverTransaction).jsonObject)
        assertEquals(BigDecimal("1234.50"), entity.amount)
        assertEquals(42, entity.rev)
        assertTrue(entity.autoCategorized)
        assertNull(entity.deletedAt)
        assertNull(entity.toAccountId)
        assertEquals(Dates.parseInstant("2026-11-20T09:30:00Z"), entity.occurredAt)
        assertEquals(Dates.parseInstant("2026-11-20T10:00:05.123Z"), entity.updatedAt)
    }

    /** В очередь синхронизации попадают только поля принимаемые сервером. */
    @Test
    fun syncPayloadContainsOnlyFieldsAcceptedByServer() {
        val entity = adapter.decode(AppJson.parseToJsonElement(serverTransaction).jsonObject)
        val payload = adapter.payload(entity)
        assertEquals(
            setOf("kind", "amount", "currency", "account_id", "category_id", "occurred_at", "comment", "merchant", "source"),
            payload.keys,
        )
        // Суммы передаются строками, моменты времени – в формате ISO 8601 (UTC)
        assertEquals(JsonPrimitive("1234.50"), payload["amount"])
        assertEquals(JsonPrimitive("2026-11-20T09:30:00Z"), payload["occurred_at"])
    }

    /** Десятичное число принимается и строкой и числом. */
    @Test
    fun decimalIsAcceptedAsStringOrNumber() {
        val json = """{"id":"t","asset_type":"crypto","ticker":"BTC","quantity":"0.015","price":12,"fee":0.5,"traded_at":"2026-11-20T09:30:00"}"""
        val trade = AppJson.decodeFromString(TradeEntity.serializer(), json)
        assertEquals(BigDecimal("0.015"), trade.quantity)
        assertEquals(0, BigDecimal("12").compareTo(trade.price))
        assertEquals(0, BigDecimal("0.5").compareTo(trade.fee))
        // Время без часового пояса считается временем UTC
        assertEquals(Dates.parseInstant("2026-11-20T09:30:00Z"), trade.tradedAt)
        assertEquals("buy", trade.side)
    }

    /** Ответ синхронизации разбирается. */
    @Test
    fun syncResponseIsDecoded() {
        val response = AppJson.decodeFromString(
            SyncResponse.serializer(),
            """{"rev":7,"server_time":"2026-11-20T10:00:00Z","results":[{"op_id":"o1","entity":"account","id":"a1","status":"conflict",
               "message":"Запись изменена на другом устройстве позже"}],"changes":{"account":[{"id":"a1","name":"Карта"}]}}""",
        )
        assertEquals(7, response.rev)
        assertEquals("conflict", response.results.single().status)
        assertEquals(1, response.changes.getValue("account").size)
    }

    /** Текст ошибки сервера извлекается из ответа. */
    @Test
    fun serverErrorTextIsExtracted() {
        assertEquals("Неверный пароль", ApiError.detail("""{"detail":"Неверный пароль"}"""))
        assertEquals(
            "Value error, Некорректный адрес; Field required",
            ApiError.detail("""{"detail":[{"loc":["body","email"],"msg":"Value error, Некорректный адрес"},{"msg":"Field required"}]}"""),
        )
        assertNull(ApiError.detail("<html>502</html>"))
        assertNull(ApiError.detail(null))
        assertTrue(ApiError(0, "x").isOffline)
        assertTrue(ApiError(503, "x").isMaintenance)
        assertFalse(ApiError(500, "x").isMaintenance)
    }

    /** Идентификаторы системных категорий совпадают с серверными. */
    @Test
    fun systemCategoryIdsMatchServer() {
        // Значения получены функцией uuid.uuid5 серверной части (services/categories.py)
        assertEquals("21ad86b0-18fb-5f94-8205-73ed588821a3", SystemCategories.id("Продукты"))
        assertEquals("cb920713-a25e-58e5-82d4-bbeadafc6bdb", SystemCategories.id("Кафе/Кофейни"))
        assertEquals("91e3a475-bd25-5a23-a256-fc0745498b40", SystemCategories.id("Зарплата"))
        val all = SystemCategories.all()
        assertEquals(39, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.isSystem })
        val coffee = all.first { it.name == "Кофейни" }
        assertEquals(SystemCategories.id("Кафе"), coffee.parentId)
        assertEquals("expense", coffee.kind)
    }

    /** Uuid5 соответствует стандарту RFC 4122. */
    @Test
    fun uuid5FollowsRfc4122() {
        // Контрольный пример: uuid5(NAMESPACE_DNS, "python.org")
        val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals("886313e1-3b8a-5372-9b90-0c9aee199e5d", Ids.uuid5(dns, "python.org"))
    }
}
