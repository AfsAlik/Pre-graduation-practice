package ru.finhelper.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.finhelper.app.MainActivity
import ru.finhelper.app.R

/** Показ уведомлений Android. Каналы соответствуют видам уведомлений (п. 4.2.1.6 а ТЗ). */
class Notifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(
            CHANNEL_BUDGET to R.string.channel_budget,
            CHANNEL_GOALS to R.string.channel_goals,
            CHANNEL_PAYMENTS to R.string.channel_payments,
            CHANNEL_GENERAL to R.string.channel_general,
        ).forEach { (id, title) ->
            manager?.createNotificationChannel(NotificationChannel(id, context.getString(title), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /**
     * Показывает уведомление; нажатие открывает приложение на экране [route].
     * Если пользователь не разрешил уведомления, вызов игнорируется.
     */
    fun show(key: String, channel: String, title: String, body: String, route: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ROUTE, route)
        val pending = PendingIntent.getActivity(
            context, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_BUDGET = "budget"
        const val CHANNEL_GOALS = "goals"
        const val CHANNEL_PAYMENTS = "payments"
        const val CHANNEL_GENERAL = "general"

        /** Параметр намерения: экран, который нужно открыть по нажатию на уведомление. */
        const val EXTRA_ROUTE = "route"
    }
}
