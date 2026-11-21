package ru.finhelper.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import ru.finhelper.app.FinHelperApp

/**
 * Фоновая синхронизация средствами WorkManager (п. 4.2.2.11 ТЗ): выполняется при
 * наличии сети, в том числе когда приложение закрыто.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FinHelperApp).container
        container.localAlerts.check()
        if (container.session.session.value == null) return Result.success()
        return if (container.syncManager.syncNow()) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC_WORK = "finhelper_periodic_sync"
        private const val ONE_TIME_WORK = "finhelper_sync_now"
        private val CONNECTED = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Регистрирует периодическую синхронизацию (минимальный интервал WorkManager – 15 минут). */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(CONNECTED).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Ставит разовую синхронизацию: выполнится, как только появится сеть. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(CONNECTED)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
