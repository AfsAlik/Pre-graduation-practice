package ru.finhelper.app.notify

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.finhelper.app.BuildConfig
import ru.finhelper.app.FinHelperApp

/**
 * Подключение Firebase Cloud Messaging без файла google-services.json.
 *
 * Параметры проекта Firebase задаются свойствами Gradle (fcmProjectId, fcmApplicationId,
 * fcmApiKey, fcmSenderId). Пока они не заданы, сервис не инициализируется и приложение
 * работает без push-уведомлений сервера: уведомления формируются на устройстве и
 * загружаются при синхронизации.
 */
object PushSetup {
    /** Заданы ли параметры проекта Firebase. */
    val isConfigured: Boolean
        get() = BuildConfig.FCM_PROJECT_ID.isNotBlank() && BuildConfig.FCM_APPLICATION_ID.isNotBlank() &&
            BuildConfig.FCM_API_KEY.isNotBlank()

    fun initialize(context: Context) {
        if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setApplicationId(BuildConfig.FCM_APPLICATION_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(context, options)
    }

    /** Токен доставки push-уведомлений; пустая строка, если FCM не настроен или недоступен. */
    suspend fun token(context: Context): String {
        if (!isConfigured || FirebaseApp.getApps(context).isEmpty()) return ""
        return suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result.orEmpty() else "")
            }
        }
    }
}

/** Прием push-уведомлений сервера (п. 4.1.3.4 ТЗ). */
class PushService : FirebaseMessagingService() {
    private val container get() = (application as FinHelperApp).container

    /** Новый токен устройства передается серверу. */
    override fun onNewToken(token: String) {
        container.appScope.launch { container.auth.registerDevice() }
    }

    /** Показывает уведомление и запускает синхронизацию, чтобы данные на экране были актуальны. */
    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"].orEmpty()
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"].orEmpty()
        if (kind !in LocalAlertRules.LOCAL_KINDS) {
            container.notifier.show(message.messageId ?: title, LocalAlerts.channelOf(kind), title, body, LocalAlerts.routeOf(kind))
        }
        container.syncManager.requestSync()
    }
}
