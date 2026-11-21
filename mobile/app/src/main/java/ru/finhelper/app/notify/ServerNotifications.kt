package ru.finhelper.app.notify

import ru.finhelper.app.data.local.NotificationDao
import ru.finhelper.app.data.local.NotificationEntity
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.apiCall

/**
 * Получение уведомлений сервера (GET /notifications) при синхронизации: новые
 * записи сохраняются в локальной базе и показываются пользователю. Так уведомления
 * доходят и без настроенного сервиса Firebase Cloud Messaging.
 */
class ServerNotifications(
    private val api: FinHelperApi,
    private val dao: NotificationDao,
    private val notifier: Notifier,
    private val pushEnabled: () -> Boolean,
) {
    suspend fun pull() {
        val response = apiCall { api.notifications() }.getOrNull() ?: return
        val known = dao.allIds().toSet()
        for (item in response.items.filter { it.id !in known }) {
            // Уведомления о бюджете, целях и платежах устройство формирует само – они только сохраняются
            val alreadyShown = item.kind in LocalAlertRules.LOCAL_KINDS
            dao.upsert(NotificationEntity(item.id, item.kind, item.title, item.body, item.createdAt, read = item.read || alreadyShown))
            if (!alreadyShown && !item.read && pushEnabled()) {
                notifier.show(item.id, LocalAlerts.channelOf(item.kind), item.title, item.body, LocalAlerts.routeOf(item.kind))
            }
        }
    }
}
