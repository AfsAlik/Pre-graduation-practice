package ru.finhelper.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.ImportResultDto
import ru.finhelper.app.data.remote.apiCall
import java.io.IOException
import java.time.LocalDate

/**
 * Импорт и экспорт файлов (п. 4.2.1.7 а, б ТЗ): файл выбирается системным
 * диалогом, разбор выписки и формирование отчета выполняет сервер.
 */
class FileRepository(private val context: Context, private val api: FinHelperApi) {
    private val octetStream = "application/octet-stream".toMediaType()
    private val textPlain = "text/plain".toMediaType()

    /** Импортирует банковскую выписку CSV, OFX или QIF на указанный счет. */
    suspend fun importStatement(uri: Uri, accountId: String): Result<ImportResultDto> = apiCall {
        api.importStatement(accountId.toRequestBody(textPlain), filePart(uri))
    }

    /** Сохраняет историю операций за период в выбранный пользователем файл (PDF или XLSX). */
    suspend fun exportTransactions(target: Uri, format: String, start: LocalDate, end: LocalDate): Result<Unit> = apiCall {
        write(target, api.exportTransactions(format, start.toString(), end.toString()))
    }

    /** Сохраняет отчет по категориям за период с учетом фильтров. */
    suspend fun exportReport(
        target: Uri, format: String, start: LocalDate, end: LocalDate, accountIds: List<String>, categoryIds: List<String>,
    ): Result<Unit> = apiCall {
        write(target, api.exportReport(format, start.toString(), end.toString(), accountIds, categoryIds))
    }

    /** Сохраняет журнал аудита в файл CSV. */
    suspend fun exportLogs(target: Uri, dateFrom: String?, dateTo: String?, user: String?, action: String?): Result<Unit> = apiCall {
        write(target, api.adminExportLogs(dateFrom, dateTo, user, action))
    }

    /** Загружает внешний журнал из файла CSV; возвращает число импортированных записей. */
    suspend fun importLogs(uri: Uri): Result<Int> = apiCall { api.adminImportLogs(filePart(uri)).imported }

    private suspend fun filePart(uri: Uri): MultipartBody.Part = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Не удалось прочитать файл")
        MultipartBody.Part.createFormData("file", fileName(uri), bytes.toRequestBody(octetStream))
    }

    private suspend fun write(target: Uri, body: ResponseBody) = withContext(Dispatchers.IO) {
        body.use { source ->
            context.contentResolver.openOutputStream(target)?.use { output -> source.byteStream().copyTo(output) }
                ?: throw IOException("Не удалось сохранить файл")
        }
        Unit
    }

    /** Имя файла нужно серверу, чтобы определить формат выписки по расширению. */
    private fun fileName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "file"
    }
}
