package ru.finhelper.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/** Проверка форматирования и разбора денежных сумм (п. 4.4.5.17, 4.4.5.18 ТЗ). */
class MoneyTest {
    private val nbsp = Money.GROUP_SEPARATOR

    /** Сумма выводится с разделителями разрядов и обозначением валюты. */
    @Test
    fun amountIsFormattedWithGroupingAndCurrency() {
        assertEquals("1${nbsp}234,56$nbsp₽", Money.format(BigDecimal("1234.56"), "RUB"))
        assertEquals("1${nbsp}200,00$nbsp$", Money.format(BigDecimal("1200"), "USD"))
        assertEquals("428${nbsp}771$nbsp₽", Money.format(BigDecimal("428771.00"), "RUB", kopecks = false))
        assertEquals("0,00", Money.format(BigDecimal.ZERO))
    }

    /** Знак суммы выводится для расходов и по запросу для доходов. */
    @Test
    fun signIsShownForExpensesAndOnRequestForIncome() {
        assertEquals("−500,00$nbsp₽", Money.format(BigDecimal("-500"), "RUB"))
        assertEquals("+100,00", Money.format(BigDecimal("100"), signed = true))
        assertEquals("0,00", Money.format(BigDecimal.ZERO, signed = true))
    }

    /** Сумма округляется до копеек по математическим правилам. */
    @Test
    fun amountIsRoundedHalfUpToKopecks() {
        assertEquals("10,13", Money.format(BigDecimal("10.125")))
        assertEquals("10,12", Money.format(BigDecimal("10.124")))
    }

    /** Проценты и количество форматируются без лишних нулей. */
    @Test
    fun percentAndQuantityFormatting() {
        assertEquals("86,8$nbsp%", Money.formatPercent(86.79))
        assertEquals("+7,4$nbsp%", Money.formatPercent(7.41, signed = true))
        assertEquals("0,015", Money.formatQuantity(BigDecimal("0.01500000")))
        assertEquals("1${nbsp}500", Money.formatQuantity(BigDecimal("1500")))
    }

    /** Разбор принимает запятую точку и пробелы. */
    @Test
    fun parseAcceptsCommaDotAndSpaces() {
        assertEquals(BigDecimal("1234.56"), Money.parse("1 234,56"))
        assertEquals(BigDecimal("1234.56"), Money.parse("1${nbsp}234.56"))
        assertEquals(BigDecimal("12.50"), Money.parse("12.5"))
        assertEquals(BigDecimal("350.00"), Money.parse("350"))
    }

    /** Некорректная сумма не разбирается. */
    @Test
    fun invalidAmountIsNotParsed() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1,234"))
        assertNull(Money.parse("-5"))
        assertNull(Money.parse("1,2,3"))
    }

    /** Ввод очищается от лишних символов. */
    @Test
    fun inputIsSanitized() {
        assertEquals("123,45", Money.sanitizeInput("12a3,4,56"))
        assertEquals("0,5", Money.sanitizeInput(",5"))
        assertEquals("1500", Money.sanitizeInput("1 500 руб"))
        assertEquals("10,", Money.sanitizeInput("10."))
    }

    /** Сумма преобразуется в текст поля ввода. */
    @Test
    fun amountIsConvertedToInputText() {
        assertEquals("1250,50", Money.toInput(BigDecimal("1250.50")))
        assertEquals("1000", Money.toInput(BigDecimal("1000.00")))
        assertEquals("", Money.toInput(null))
        assertEquals("", Money.toInput(BigDecimal.ZERO))
    }

    /** Маска поля суммы группирует разряды и сохраняет позицию курсора. */
    @Test
    fun moneyMaskGroupsDigitsAndKeepsCursor() {
        assertEquals("1${nbsp}234${nbsp}567", Money.groupDigits("1234567"))
        assertEquals("999", Money.groupDigits("999"))
        // «1234567,5» отображается как «1 234 567,5»
        assertArrayEquals(intArrayOf(0, 1, 3, 4, 5, 7, 8, 9, 10, 11), Money.groupedOffsets("1234567,5"))
        assertArrayEquals(intArrayOf(0, 1, 2, 3), Money.groupedOffsets("123"))
        val shown = MoneyVisualTransformation().filter(androidx.compose.ui.text.AnnotatedString("1234567,5"))
        assertEquals("1${nbsp}234${nbsp}567,5", shown.text.text)
        assertEquals(1, shown.offsetMapping.transformedToOriginal(2))
        assertEquals(11, shown.offsetMapping.originalToTransformed(9))
    }
}
