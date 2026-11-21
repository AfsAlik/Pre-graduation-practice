package ru.finhelper.app.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.GoalContributionEntity
import ru.finhelper.app.data.local.GoalEntity

/** Показатели финансовой цели (п. 4.2.1.2 д–ж ТЗ). */
data class GoalProgress(
    val goal: GoalEntity,
    val saved: BigDecimal,
    val remaining: BigDecimal,
    /** Прогресс в процентах с точностью до десятых, не более 100. */
    val percent: Double,
    /** Прогнозируемая дата достижения; null – накоплений еще нет. */
    val forecastDate: LocalDate?,
    /** Рекомендуемая сумма ежемесячных накоплений для достижения цели в срок. */
    val recommendedMonthly: BigDecimal,
    /** Цель будет достигнута не позже установленного срока. */
    val onTrack: Boolean,
    /** Достигнутая цель доступна только для просмотра. */
    val readOnly: Boolean,
)

/** Расчет прогресса цели; формулы совпадают с серверной частью (services/goals.py). */
object GoalCalculator {
    const val STATUS_ACHIEVED = "achieved"
    private const val MIN_PACE_DAYS = 30L

    fun saved(goalId: String, contributions: List<GoalContributionEntity>): BigDecimal = contributions
        .filter { it.goalId == goalId && it.deletedAt == null }
        .fold(BigDecimal.ZERO) { total, item -> total + item.amount }

    /**
     * Прогресс – доля накопленной суммы от целевой. Прогнозируемая дата определяется
     * по среднему темпу накоплений с даты первого взноса (темп оценивается не менее
     * чем за 30 дней). Рекомендуемая сумма – остаток, деленный на число месяцев до срока.
     */
    fun progress(goal: GoalEntity, contributions: List<GoalContributionEntity>, today: LocalDate): GoalProgress {
        val own = contributions.filter { it.goalId == goal.id && it.deletedAt == null }
        val target = goal.targetAmount
        val saved = own.fold(BigDecimal.ZERO) { total, item -> total + item.amount }
        val remaining = (target - saved).max(BigDecimal.ZERO)
        val percent = if (target.signum() > 0) {
            saved.multiply(BigDecimal(100)).divide(target, MathContext.DECIMAL64).min(BigDecimal(100)).toDouble()
        } else {
            0.0
        }
        val deadline = Dates.parseDate(goal.deadline)
        val monthsLeft = if (deadline.isAfter(today)) Periods.monthsBetween(today, deadline) else 1
        val recommended = if (remaining.signum() > 0) {
            remaining.divide(BigDecimal(monthsLeft), 2, RoundingMode.UP)
        } else {
            BigDecimal.ZERO
        }
        val forecast: LocalDate? = when {
            remaining.signum() == 0 -> goal.achievedAt?.let(Dates::toLocalDate) ?: today
            saved.signum() > 0 -> {
                val start = own.minOfOrNull { it.occurredAt }?.let(Dates::toLocalDate) ?: Dates.toLocalDate(goal.createdAt)
                val days = maxOf(ChronoUnit.DAYS.between(start, today), MIN_PACE_DAYS)
                // Число дней = остаток / (накоплено / дней); вычисляется одним делением без потери точности
                today.plusDays(remaining.multiply(BigDecimal(days)).divide(saved, 0, RoundingMode.CEILING).toLong())
            }
            else -> null
        }
        return GoalProgress(
            goal = goal,
            saved = saved.setScale(2, RoundingMode.HALF_UP),
            remaining = remaining.setScale(2, RoundingMode.HALF_UP),
            percent = BigDecimal.valueOf(percent).setScale(1, RoundingMode.HALF_UP).toDouble(),
            forecastDate = forecast,
            recommendedMonthly = recommended,
            onTrack = remaining.signum() == 0 || (forecast != null && !forecast.isAfter(deadline)),
            readOnly = goal.status == STATUS_ACHIEVED || remaining.signum() == 0,
        )
    }
}
