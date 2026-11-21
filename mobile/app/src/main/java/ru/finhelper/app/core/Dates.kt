package ru.finhelper.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Преобразование и представление дат. Моменты времени хранятся в миллисекундах UTC. */
object Dates {
    private val RUSSIAN = Locale("ru")
    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", RUSSIAN)
    private val SHORT = DateTimeFormatter.ofPattern("dd.MM")
    private val MONTH_NAMES = listOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )

    /** Часовой пояс устройства; в тестах подменяется для воспроизводимости. */
    var zone: ZoneId = ZoneId.systemDefault()

    /** Разбирает момент времени в формате ISO 8601 (с часовым поясом или без него – тогда UTC). */
    fun parseInstant(text: String): Long = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    fun isoInstant(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    fun toLocalDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun startOfDay(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDay(date: LocalDate): Long = startOfDay(date.plusDays(1)) - 1

    /** Полдень указанной даты: используется как время операции при выборе даты в форме. */
    fun noon(date: LocalDate): Long = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    fun parseDate(text: String): LocalDate = LocalDate.parse(text)

    fun format(date: LocalDate): String = date.format(DATE)

    fun format(millis: Long): String = format(toLocalDate(millis))

    fun formatDateTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().format(DATE_TIME)

    /** «5 ноября» – заголовок группы операций одного дня. */
    fun formatDayMonth(date: LocalDate): String = date.format(DAY_MONTH)

    fun formatShort(date: LocalDate): String = date.format(SHORT)

    /** «Ноябрь 2026». */
    fun monthTitle(month: YearMonth): String = "${MONTH_NAMES[month.monthValue - 1]} ${month.year}"

    /** Сокращенное название месяца для подписей графиков: «ноя». */
    fun monthShort(month: YearMonth): String = MONTH_NAMES[month.monthValue - 1].take(3).lowercase(RUSSIAN)
}
