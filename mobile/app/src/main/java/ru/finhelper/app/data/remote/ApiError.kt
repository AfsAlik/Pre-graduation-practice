package ru.finhelper.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException
import java.io.IOException

/**
 * Ошибка обращения к серверу в виде, пригодном для показа пользователю.
 *
 * @property code код ответа HTTP; 0 – сервер недоступен (нет сети)
 * @property twoFactorRequired сервер запросил код двухфакторной аутентификации
 */
class ApiError(
    val code: Int,
    override val message: String,
    val twoFactorRequired: Boolean = false,
) : Exception(message) {
    /** Нет соединения с сервером. */
    val isOffline: Boolean get() = code == 0

    /** Сервер переведен в режим технического обслуживания (п. 4.1.4.4 ТЗ). */
    val isMaintenance: Boolean get() = code == 503

    companion object {
        const val OFFLINE_MESSAGE = "Нет соединения с сервером. Проверьте подключение к сети"

        /** Преобразует исключение сетевого слоя в [ApiError]. */
        fun from(error: Throwable): ApiError = when (error) {
            is ApiError -> error
            is HttpException -> ApiError(
                code = error.code(),
                message = detail(error.response()?.errorBody()?.string()) ?: defaultMessage(error.code()),
                twoFactorRequired = error.response()?.headers()?.get("X-2FA-Required") == "true",
            )
            is IOException -> ApiError(0, OFFLINE_MESSAGE)
            else -> ApiError(-1, error.message ?: "Неизвестная ошибка")
        }

        /** Извлекает текст ошибки из ответа вида {"detail": "..."} или {"detail": [{"msg": "..."}]}. */
        fun detail(body: String?): String? {
            if (body.isNullOrBlank()) return null
            return try {
                when (val detail = AppJson.parseToJsonElement(body).jsonObject["detail"]) {
                    is JsonPrimitive -> detail.content
                    is JsonArray -> detail.mapNotNull { ((it as? JsonObject)?.get("msg") as? JsonPrimitive)?.content }
                        .joinToString("; ").ifBlank { null }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun defaultMessage(code: Int): String = when (code) {
            401 -> "Сеанс завершен. Выполните вход повторно"
            403 -> "Недостаточно прав для выполнения действия"
            404 -> "Запись не найдена"
            423 -> "Вход временно заблокирован. Повторите попытку позже"
            503 -> "Выполняются технические работы. Данные сохраняются на устройстве"
            else -> "Ошибка сервера ($code)"
        }
    }
}

/** Выполняет обращение к серверу и возвращает результат либо [ApiError]. */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(ApiError.from(error))
}

/** Текст ошибки для показа пользователю. */
fun Throwable.userMessage(): String = ApiError.from(this).message
