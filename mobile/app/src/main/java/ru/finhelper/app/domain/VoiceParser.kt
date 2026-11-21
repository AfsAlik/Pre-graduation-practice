package ru.finhelper.app.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Результат разбора голосовой фразы. */
data class ParsedVoice(val kind: String, val amount: BigDecimal?, val description: String)

/**
 * Разбор распознанной голосовой фразы вида «потратил 500 рублей на кафе» или
 * «получил зарплату 80 тысяч» (п. 4.2.1.1 б ТЗ).
 *
 * Работает на устройстве без сети; правила повторяют серверный модуль
 * services/textparse.py.
 */
object VoiceParser {
    private val INCOME_WORDS = listOf(
        "получил", "получила", "зарплат", "доход", "пришл", "зачисл", "вернули",
        "аванс", "преми", "заработал", "кэшбэк", "кешбэк", "дивиденд",
    )
    private val UNITS = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14,
        "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90, "сто" to 100,
        "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500, "шестьсот" to 600,
        "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900, "полтораста" to 150,
    )
    private val THOUSANDS = setOf("тысяча", "тысячи", "тысяч", "тыс", "тыщ", "тыщи", "косарь", "косаря")
    private val MILLIONS = setOf("миллион", "миллиона", "миллионов", "млн")
    private val HALVES = setOf("полторы", "полтора")

    /** Число цифрами с необязательным множителем: «500», «1 500,50», «80 тысяч», «5к», «2 млн». */
    private val DIGITS = Regex("""(\d[\d\s]*(?:[.,]\d{1,2})?)\s*(тыс[а-я]*|тыщ[а-я]*|к(?![а-яa-z0-9])|млн|миллион[а-я]*)?""")
    private val WORD = Regex("[а-яa-z0-9]+")
    private val CURRENCY_WORD = Regex("рубл[а-я]*|руб|р|доллар[а-я]*|евро|юан[а-я]*|копе[а-я]*")
    private val FILLER_WORD = Regex(
        "потратил[а-я]*|купил[а-я]*|заплатил[а-я]*|оплатил[а-я]*|получил[а-я]*|на|за|в|из|по|с|мне|я|сегодня|вчера|расход|доход"
    )

    /**
     * Преобразует числительные в число.
     * @return число (или null) и количество разобранных слов
     */
    fun wordsToNumber(words: List<String>): Pair<BigDecimal?, Int> {
        var total = BigDecimal.ZERO
        var current = BigDecimal.ZERO
        var used = 0
        for (word in words) {
            when (word) {
                in UNITS -> current += BigDecimal(UNITS.getValue(word))
                in THOUSANDS -> {
                    total += (if (current.signum() == 0) BigDecimal.ONE else current) * BigDecimal(1_000)
                    current = BigDecimal.ZERO
                }
                in MILLIONS -> {
                    total += (if (current.signum() == 0) BigDecimal.ONE else current) * BigDecimal(1_000_000)
                    current = BigDecimal.ZERO
                }
                in HALVES -> current = BigDecimal("1.5") // «полторы тысячи»
                else -> break
            }
            used++
        }
        total += current
        val number = if (used > 0 && total.signum() > 0) total.setScale(2, RoundingMode.HALF_UP) else null
        return number to used
    }

    fun parse(text: String): ParsedVoice {
        val lowered = text.lowercase().replace('ё', 'е')
        val kind = if (INCOME_WORDS.any { it in lowered }) BalanceCalculator.INCOME else BalanceCalculator.EXPENSE
        var amount: BigDecimal? = null
        var rest = lowered
        val match = DIGITS.find(lowered)
        if (match != null) {
            amount = match.groupValues[1].filterNot { it.isWhitespace() }.replace(',', '.').toBigDecimalOrNull()
                ?.setScale(2, RoundingMode.HALF_UP)
            val multiplier = match.groupValues[2]
            if (amount != null && multiplier.isNotEmpty()) {
                val factor = if (multiplier.startsWith("млн") || multiplier.startsWith("миллион")) 1_000_000 else 1_000
                amount = amount.multiply(BigDecimal(factor))
            }
            rest = lowered.substring(0, match.range.first) + " " + lowered.substring(match.range.last + 1)
        } else {
            val tokens = WORD.findAll(lowered).map { it.value }.toList()
            for (index in tokens.indices) {
                val (number, used) = wordsToNumber(tokens.subList(index, tokens.size))
                if (number != null) {
                    amount = number
                    rest = (tokens.subList(0, index) + tokens.subList(index + used, tokens.size)).joinToString(" ")
                    break
                }
            }
        }
        val description = WORD.findAll(rest).map { it.value }
            .filterNot { CURRENCY_WORD.matches(it) || FILLER_WORD.matches(it) }
            .joinToString(" ")
        return ParsedVoice(kind, amount, description)
    }
}
