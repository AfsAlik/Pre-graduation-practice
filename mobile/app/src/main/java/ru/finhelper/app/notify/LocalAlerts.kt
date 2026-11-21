package ru.finhelper.app.notify

import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.local.NotificationDao
import ru.finhelper.app.data.local.NotificationEntity
import ru.finhelper.app.data.local.RecurringPaymentDao
import ru.finhelper.app.data.local.RecurringPaymentEntity
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.repo.FinanceSnapshot
import ru.finhelper.app.domain.BudgetCalculator
import ru.finhelper.app.domain.GoalCalculator
import ru.finhelper.app.domain.Periods
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Локальное уведомление, сформированное на устройстве. */
data class LocalAlert(
    /** Ключ события: по нему уведомление показывается один раз за период. */
    val key: String,
    val kind: String,
    val title: String,
    val body: String,
)

/**
 * Правила формирования уведомлений на устройстве (п. 4.2.1.2 в, 4.2.1.6 а ТЗ):
 * приближение к бюджетному лимиту и его превышение, достижение цели, предстоящий
 * регулярный платеж. Уведомления формируются и без подключения к сети.
 */
object LocalAlertRules {
    const val KIND_BUDGET_WARNING = "budget_warning"
    const val KIND_BUDGET_EXCEEDED = "budget_exceeded"
    const val KIND_GOAL_ACHIEVED = "goal_achieved"
    const val KIND_RECURRING = "recurring_payment"

    /** Виды уведомлений, которые устройство формирует само (серверные копии повторно не показываются). */
    val LOCAL_KINDS = setOf(KIND_BUDGET_WARNING, KIND_BUDGET_EXCEEDED, KIND_GOAL_ACHIEVED, KIND_RECURRING)

    /** Давность достижения цели, после которой уведомление уже не показывается. */
    private const val GOAL_FRESH_DAYS = 3L

    fun compute(snapshot: FinanceSnapshot, recurring: List<RecurringPaymentEntity>, today: LocalDate): List<LocalAlert> {
        val settings = snapshot.profile.notificationSettings
        val alerts = mutableListOf<LocalAlert>()
        if (settings.budget) {
            val statuses = BudgetCalculator.overview(
                snapshot.budgets, snapshot.categoryMap, snapshot.transactions, today, snapshot.converter, settings.budgetThresholdPercent,
            )
            for (status in statuses) {
                val period = Periods.key(status.budget.period, today)
                val limit = Money.format(status.budget.limitAmount, status.budget.currency)
                if (status.percent > 100) {
                    alerts += LocalAlert(
                        "exceeded:${status.budget.id}:$period", KIND_BUDGET_EXCEEDED,
                        "Превышен бюджет: ${status.categoryName}",
                        "Израсходовано ${Money.format(status.spent, status.budget.currency)} из $limit (${Money.formatPercent(status.percent)}).",
                    )
                } else if (status.warning) {
                    alerts += LocalAlert(
                        "warning:${status.budget.id}:$period", KIND_BUDGET_WARNING,
                        "Бюджет почти исчерпан: ${status.categoryName}",
                        "Израсходовано ${Money.formatPercent(status.percent)} лимита, осталось " +
                            "${Money.format(status.remaining, status.budget.currency)}.",
                    )
                }
            }
        }
        if (settings.goals) {
            for (goal in snapshot.goals) {
                val progress = GoalCalculator.progress(goal, snapshot.contributions, today)
                val achievedOn = goal.achievedAt?.let(Dates::toLocalDate) ?: today
                if (progress.remaining.signum() == 0 && ChronoUnit.DAYS.between(achievedOn, today) <= GOAL_FRESH_DAYS) {
                    alerts += LocalAlert(
                        "goal:${goal.id}", KIND_GOAL_ACHIEVED, "Цель достигнута: ${goal.name}",
                        "Накоплено ${Money.format(goal.targetAmount, goal.currency)}. Поздравляем!",
                    )
                }
            }
        }
        if (settings.recurring) {
            for (payment in recurring) {
                if (!payment.isActive || payment.deletedAt != null) continue
                val date = Dates.parseDate(payment.nextDate)
                val daysLeft = ChronoUnit.DAYS.between(today, date)
                if (daysLeft in 0..payment.remindDaysBefore) {
                    val whenText = when (daysLeft) {
                        0L -> "сегодня"
                        1L -> "завтра"
                        else -> "через $daysLeft дн."
                    }
                    alerts += LocalAlert(
                        "recurring:${payment.id}:${payment.nextDate}", KIND_RECURRING, "Предстоящий платеж: ${payment.name}",
                        "${Money.format(payment.amount, payment.currency)} – $whenText (${Dates.format(date)}).",
                    )
                }
            }
        }
        return alerts
    }
}

/**
 * Показ локальных уведомлений: проверяет условия после каждого изменения данных и
 * в фоновой задаче, каждое событие показывает один раз.
 */
class LocalAlerts(
    private val snapshot: () -> FinanceSnapshot,
    private val recurringPayments: RecurringPaymentDao,
    private val notifications: NotificationDao,
    private val settings: SettingsStore,
    private val notifier: Notifier,
) {
    suspend fun check() {
        val current = snapshot()
        if (!current.loaded) return
        val alerts = LocalAlertRules.compute(current, recurringPayments.active(), Dates.toLocalDate(System.currentTimeMillis()))
        val shown = settings.shownAlerts
        val fresh = alerts.filter { it.key !in shown }
        if (fresh.isEmpty()) return
        for (alert in fresh) {
            notifications.upsert(NotificationEntity("local:${alert.key}", alert.kind, alert.title, alert.body, System.currentTimeMillis()))
            if (current.profile.notificationSettings.pushEnabled) {
                notifier.show(alert.key, channelOf(alert.kind), alert.title, alert.body, routeOf(alert.kind))
            }
        }
        // Ключи прошедших периодов больше не понадобятся – хранится ограниченное число последних
        settings.shownAlerts = (shown + fresh.map { it.key }).toList().takeLast(MAX_KEYS).toSet()
    }

    companion object {
        private const val MAX_KEYS = 500

        fun channelOf(kind: String): String = when (kind) {
            LocalAlertRules.KIND_BUDGET_WARNING, LocalAlertRules.KIND_BUDGET_EXCEEDED -> Notifier.CHANNEL_BUDGET
            LocalAlertRules.KIND_GOAL_ACHIEVED -> Notifier.CHANNEL_GOALS
            LocalAlertRules.KIND_RECURRING -> Notifier.CHANNEL_PAYMENTS
            else -> Notifier.CHANNEL_GENERAL
        }

        /** Экран, открываемый по нажатию на уведомление данного вида. */
        fun routeOf(kind: String): String = when (kind) {
            LocalAlertRules.KIND_BUDGET_WARNING, LocalAlertRules.KIND_BUDGET_EXCEEDED -> "budget"
            LocalAlertRules.KIND_GOAL_ACHIEVED -> "goals"
            LocalAlertRules.KIND_RECURRING -> "recurring"
            "recommendation", "risk" -> "recommendations"
            else -> "notifications"
        }
    }
}
