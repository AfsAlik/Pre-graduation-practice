package ru.finhelper.app.core

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Форматирование и разбор денежных сумм.
 *
 * Суммы во всем приложении представлены типом [BigDecimal]; числа с плавающей
 * точкой для денег не используются, чтобы исключить ошибки округления.
 */
object Money {
    /** Неразрывный пробел – разделитель разрядов. */
    const val GROUP_SEPARATOR = ' '
    private const val MINUS = '−'
    private const val MAX_INTEGER_DIGITS = 13

    /** Обозначение валюты по коду ISO 4217. */
    fun symbol(currency: String): String = when (currency) {
        "RUB" -> "₽"
        "USD" -> "$"
        "EUR" -> "€"
        "CNY" -> "¥"
        "KZT" -> "₸"
        "GBP" -> "£"
        "BYN" -> "Br"
        else -> currency
    }

    /**
     * Представляет сумму в виде «1 234,56 ₽».
     *
     * @param kopecks выводить ли дробную часть
     * @param signed выводить ли знак «+» перед положительной суммой
     */
    fun format(
        amount: BigDecimal,
        currency: String? = null,
        kopecks: Boolean = true,
        signed: Boolean = false,
    ): String {
        val number = formatNumber(amount.abs(), if (kopecks) 2 else 0)
        val sign = when {
            amount.signum() < 0 -> MINUS.toString()
            signed && amount.signum() > 0 -> "+"
            else -> ""
        }
        val suffix = currency?.let { "$GROUP_SEPARATOR${symbol(it)}" }.orEmpty()
        return sign + number + suffix
    }

    /** Число с разделителями разрядов и десятичной запятой. */
    fun formatNumber(value: BigDecimal, decimals: Int): String {
        val plain = value.setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        val negative = plain.startsWith("-")
        val parts = plain.removePrefix("-").split('.')
        val grouped = groupDigits(parts[0])
        val fraction = if (parts.size > 1) ",${parts[1]}" else ""
        return (if (negative) MINUS.toString() else "") + grouped + fraction
    }

    /** Количество (например, бумаг в портфеле) без незначащих нулей. */
    fun formatQuantity(value: BigDecimal): String {
        val stripped = value.stripTrailingZeros()
        return formatNumber(stripped, maxOf(stripped.scale(), 0))
    }

    /** Процент с одним знаком после запятой: «12,5 %». */
    fun formatPercent(value: Double, signed: Boolean = false): String {
        val number = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP)
        val sign = if (signed && number.signum() > 0) "+" else ""
        return sign + formatNumber(number, 1) + "$GROUP_SEPARATOR%"
    }

    /**
     * Разбирает сумму, введенную пользователем («1 234,56», «1234.5»).
     * Возвращает null, если строка не является корректной суммой.
     */
    fun parse(text: String): BigDecimal? {
        val cleaned = text.filterNot { it.isWhitespace() || it == GROUP_SEPARATOR }.replace(',', '.')
        if (cleaned.isEmpty() || !cleaned.matches(Regex("""\d{1,$MAX_INTEGER_DIGITS}(\.\d{0,2})?"""))) return null
        return cleaned.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * Очищает ввод в поле суммы: оставляет цифры и одну запятую,
     * не более двух знаков дробной части.
     */
    fun sanitizeInput(raw: String): String {
        val result = StringBuilder()
        var commaSeen = false
        var fractionDigits = 0
        var integerDigits = 0
        for (char in raw) {
            when {
                char.isDigit() && !commaSeen && integerDigits < MAX_INTEGER_DIGITS -> {
                    result.append(char)
                    integerDigits++
                }
                char.isDigit() && commaSeen && fractionDigits < 2 -> {
                    result.append(char)
                    fractionDigits++
                }
                (char == ',' || char == '.') && !commaSeen -> {
                    if (result.isEmpty()) result.append('0')
                    result.append(',')
                    commaSeen = true
                }
            }
        }
        return result.toString()
    }

    /** Строка для поля ввода, соответствующая сумме (без разделителей разрядов). */
    fun toInput(amount: BigDecimal?): String {
        if (amount == null || amount.signum() == 0) return ""
        return amount.stripTrailingZeros().let { if (it.scale() <= 0) it.setScale(0) else amount.setScale(2, RoundingMode.HALF_UP) }
            .toPlainString().replace('.', ',')
    }

    /** Разбивает целую часть числа на группы по три цифры. */
    fun groupDigits(digits: String): String {
        val result = StringBuilder()
        digits.forEachIndexed { index, char ->
            if (index > 0 && (digits.length - index) % 3 == 0) result.append(GROUP_SEPARATOR)
            result.append(char)
        }
        return result.toString()
    }

    /**
     * Таблица соответствия позиций курсора исходной строки и строки с
     * разделителями разрядов: элемент i – позиция в отображаемой строке.
     */
    fun groupedOffsets(input: String): IntArray {
        val integerLength = input.indexOf(',').let { if (it < 0) input.length else it }
        val offsets = IntArray(input.length + 1)
        var separators = 0
        for (position in 0..input.length) {
            offsets[position] = position + separators
            // Перед цифрой с этим номером стоит разделитель: он учитывается для следующих позиций
            if (position in 1 until integerLength && (integerLength - position) % 3 == 0) separators++
        }
        return offsets
    }
}

/**
 * Маска поля ввода суммы: отображает разделители разрядов по мере набора
 * (п. 4.4.5.18 ТЗ). Хранимое значение поля разделителей не содержит.
 */
class MoneyVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val input = text.text
        val integerLength = input.indexOf(',').let { if (it < 0) input.length else it }
        val shown = Money.groupDigits(input.substring(0, integerLength)) + input.substring(integerLength)
        val offsets = Money.groupedOffsets(input)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offsets[offset.coerceIn(0, input.length)]
            override fun transformedToOriginal(offset: Int): Int {
                val index = offsets.indexOfLast { it <= offset }
                return if (index < 0) 0 else index
            }
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}
