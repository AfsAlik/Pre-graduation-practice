package ru.finhelper.app.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Результат разбора текста чека. */
data class ParsedReceipt(val amount: BigDecimal?, val occurredAt: Long?, val merchant: String)

/**
 * Выделение суммы, даты и торговой точки из текста, распознанного на фотографии
 * чека (п. 4.2.1.1 в ТЗ). Работает на устройстве без сети; правила повторяют
 * серверный модуль services/textparse.py.
 */
object ReceiptParser {
    private val TOTAL = Regex(
        """(?:итог[оa]?|итог к оплате|к оплате|всего|сумма|total|безналичными|оплата)\s*[:=]?\s*""" +
            """(?:руб\.?|₽|rub)?\s*(\d[\d\s]*[.,]\d{2}|\d[\d\s]*)""",
        RegexOption.IGNORE_CASE,
    )
    private val MONEY = Regex("""(?<![\d.,])(\d{1,3}(?:[  ]\d{3})*[.,]\d{2}|\d+[.,]\d{2})(?![\d.,])""")
    private val DATE = Regex("""(\d{2})[./-](\d{2})[./-](\d{2,4})(?:\s+(\d{2}):(\d{2}))?""")

    /** Строка QR-кода фискального чека: t=20261120T1530&s=1234.56&fn=... */
    private val QR = Regex("""t=(\d{8}T\d{4,6}).*?s=(\d+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val SERVICE = Regex(
        """кассовый чек|чек №|приход|смена|кассир|инн|ккт|фн|фд|фп|ндс|сайт фнс|спасибо|""" +
            """добро пожаловать|адрес|www\.|http|тел\.|\d{10,}""",
        RegexOption.IGNORE_CASE,
    )
    private val LETTERS = Regex("[A-Za-zА-Яа-я]{3}")
    private val LEGAL_FORM = Regex("""^(ооо|оао|ао|пао|ип)\s+""", RegexOption.IGNORE_CASE)

    private fun toDecimal(raw: String): BigDecimal? =
        raw.filterNot { it.isWhitespace() || it == ' ' }.replace(',', '.').toBigDecimalOrNull()
            ?.setScale(2, RoundingMode.HALF_UP)?.takeIf { it.signum() > 0 }

    fun parse(text: String): ParsedReceipt {
        var amount: BigDecimal? = null
        var occurredAt: Long? = null
        QR.find(text)?.let { qr ->
            val stamp = qr.groupValues[1]
            val pattern = if (stamp.length == 15) "yyyyMMdd'T'HHmmss" else "yyyyMMdd'T'HHmm"
            occurredAt = runCatching {
                LocalDateTime.parse(stamp, DateTimeFormatter.ofPattern(pattern)).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
            amount = toDecimal(qr.groupValues[2])
        }
        if (amount == null) {
            // Сначала строки с итогом; если их нет – наибольшая сумма, найденная в тексте
            amount = TOTAL.findAll(text).mapNotNull { toDecimal(it.groupValues[1]) }.maxOrNull()
                ?: MONEY.findAll(text).mapNotNull { toDecimal(it.groupValues[1]) }.maxOrNull()
        }
        if (occurredAt == null) {
            DATE.find(text)?.let { match ->
                val (day, month, yearRaw, hour, minute) = match.destructured
                val year = yearRaw.toInt().let { if (it < 100) it + 2000 else it }
                occurredAt = try {
                    LocalDateTime.of(year, month.toInt(), day.toInt(), hour.toIntOrNull() ?: 12, minute.toIntOrNull() ?: 0)
                        .toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (_: DateTimeException) {
                    null
                }
            }
        }
        val merchant = text.lineSequence()
            .map { it.trim(' ', '*', '-', '=', '\t') }
            .firstOrNull { it.length >= 3 && !SERVICE.containsMatchIn(it) && !MONEY.containsMatchIn(it) && LETTERS.containsMatchIn(it) }
            ?.replace(LEGAL_FORM, "")?.trim('"', '«', ' ', '»')?.take(200)
            .orEmpty()
        return ParsedReceipt(amount, occurredAt, merchant)
    }
}
