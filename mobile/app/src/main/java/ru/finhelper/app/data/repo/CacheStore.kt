package ru.finhelper.app.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import ru.finhelper.app.data.local.CachedDocumentDao
import ru.finhelper.app.data.local.CachedDocumentEntity
import ru.finhelper.app.data.remote.AppJson
import ru.finhelper.app.data.remote.apiCall
import ru.finhelper.app.data.remote.userMessage

/**
 * Данные, полученные с сервера либо из сохраненной копии.
 *
 * @property savedAt момент получения данных с сервера, мс
 * @property fresh true – данные только что получены с сервера
 * @property error причина, по которой показана сохраненная копия
 */
data class Cached<T>(val value: T, val savedAt: Long, val fresh: Boolean, val error: String? = null)

/**
 * Хранилище ответов сервера для просмотра без сети: при недоступности сервера
 * используются последние сохраненные значения с датой их актуальности (п. 4.2.4.4 ТЗ).
 */
class CacheStore(private val dao: CachedDocumentDao) {

    suspend fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        dao.put(CachedDocumentEntity(key, AppJson.encodeToString(serializer, value), System.currentTimeMillis()))
    }

    suspend fun <T> get(key: String, serializer: KSerializer<T>): Cached<T>? = dao.get(key)?.decode(serializer)

    fun <T> observe(key: String, serializer: KSerializer<T>): Flow<Cached<T>?> = dao.observe(key).map { it?.decode(serializer) }

    /**
     * Загружает данные с сервера и сохраняет их; при ошибке возвращает сохраненную
     * копию. Если копии нет, возвращается ошибка.
     */
    suspend fun <T> fetch(key: String, serializer: KSerializer<T>, loader: suspend () -> T): Result<Cached<T>> {
        val loaded = apiCall { loader() }
        loaded.onSuccess { value ->
            put(key, serializer, value)
            return Result.success(Cached(value, System.currentTimeMillis(), fresh = true))
        }
        val error = loaded.exceptionOrNull()!!
        val saved = get(key, serializer) ?: return Result.failure(error)
        return Result.success(saved.copy(error = error.userMessage()))
    }

    private fun <T> CachedDocumentEntity.decode(serializer: KSerializer<T>): Cached<T>? = try {
        Cached(AppJson.decodeFromString(serializer, json), savedAt, fresh = false)
    } catch (_: Exception) {
        null // формат сохраненной копии устарел – она будет заменена при следующей загрузке
    }

    companion object {
        const val KEY_RATES = "rates"
        const val KEY_PORTFOLIO = "portfolio"
        const val KEY_RECOMMENDATIONS = "recommendations"
        const val KEY_FORECAST = "forecast"
        const val KEY_RISKS = "risks"
        const val KEY_FAQ = "faq"
        fun dynamicsKey(period: String) = "dynamics:$period"
        fun assetKey(ticker: String) = "asset:$ticker"
    }
}
