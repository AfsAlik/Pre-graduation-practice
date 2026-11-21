package ru.finhelper.app.data.repo

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Распознавание текста на фотографии чека библиотекой ML Kit Text Recognition
 * (п. 4.3.4.1 г ТЗ). Модель встроена в приложение, поэтому распознавание
 * выполняется на устройстве и не требует подключения к сети.
 */
class ReceiptRecognizer(private val context: Context) {
    /** Возвращает текст, найденный на изображении (строки разделены переводом строки). */
    suspend fun recognize(image: Uri): String = suspendCancellableCoroutine { continuation ->
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromFilePath(context, image))
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
            continuation.invokeOnCancellation { recognizer.close() }
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }
}
