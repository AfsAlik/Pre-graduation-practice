package ru.finhelper.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Границы бюджетных и отчетных периодов (совпадают с правилами серверной части). */
object Periods {
    const val WEEK = "week"
    const val MONTH = "month"
    const val YEAR = "year"

    /** Первый и последний день недели, месяца или года, в который входит [today]. */
    fun bounds(period: String, today: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        WEEK -> {
            val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            start to start.plusDays(6)
        }
        YEAR -> LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
        else -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
    }

    /** Ключ периода, например «month:2026-11-01»: по нему уведомление формируется один раз за период. */
    fun key(period: String, today: LocalDate): String = "$period:${bounds(period, today).first}"

    /** Число полных и неполных месяцев от [start] до [end] (не менее 1). */
    fun monthsBetween(start: LocalDate, end: LocalDate): Int {
        var months = (end.year - start.year) * 12 + end.monthValue - start.monthValue
        if (end.dayOfMonth > start.dayOfMonth) months++
        return maxOf(months, 1)
    }

    /** Следующая дата регулярного платежа после [date]. */
    fun next(period: String, date: LocalDate): LocalDate = when (period) {
        WEEK -> date.plusWeeks(1)
        YEAR -> date.plusYears(1)
        else -> date.plusMonths(1)
    }

    fun title(period: String): String = when (period) {
        WEEK -> "Неделя"
        YEAR -> "Год"
        else -> "Месяц"
    }
}
