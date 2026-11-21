package ru.finhelper.app.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.finhelper.app.data.local.ClientErrorDao
import ru.finhelper.app.data.local.ClientErrorEntity
import ru.finhelper.app.data.remote.ClientErrorRequest
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.apiCall

/**
 * Журнал ошибок клиентской части (п. 4.1.5.1 ТЗ): время, тип и контекст ошибки
 * записываются в локальную базу и передаются на сервер при синхронизации.
 */
class ErrorLogger(private val dao: ClientErrorDao, private val api: FinHelperApi, private val scope: CoroutineScope) {

    fun log(kind: String, message: String, context: String = "") {
        scope.launch {
            runCatching {
                dao.insert(ClientErrorEntity(at = System.currentTimeMillis(), kind = kind.take(100), message = message.take(4000), context = context))
            }
        }
    }

    fun log(error: Throwable, context: String = "") = log(error::class.java.simpleName, error.message ?: error.toString(), context)

    /** Передает на сервер неотправленные записи и удаляет записи старше 30 дней. */
    suspend fun upload() {
        val items = dao.unsent()
        val sent = items.filter { item ->
            apiCall {
                api.clientError(ClientErrorRequest(item.kind, item.message, context = mapOf("context" to item.context, "at" to item.at.toString())))
            }.isSuccess
        }
        if (sent.isNotEmpty()) dao.markSent(sent.map { it.id })
        dao.deleteOlderThan(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
    }
}
