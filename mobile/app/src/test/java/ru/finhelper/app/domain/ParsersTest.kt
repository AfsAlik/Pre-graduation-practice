package ru.finhelper.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.local.TransactionEntity
import java.math.BigDecimal

/** Проверка разбора голосовой фразы и текста чека, а также подбора категории (п. 4.2.1.1 б–г ТЗ). */
class ParsersTest {
    private fun voice(text: String) = VoiceParser.parse(text)

    // ------------------------------------------------------------ голосовой ввод
    /** Расход с суммой цифрами. */
    @Test
    fun expenseWithDigits() {
        assertEquals(ParsedVoice("expense", BigDecimal("500.00"), "кафе"), voice("Потратил 500 рублей на кафе"))
        assertEquals(ParsedVoice("expense", BigDecimal("1500.50"), "обед"), voice("1 500,50 руб обед"))
    }

    /** Доход определяется по ключевым словам. */
    @Test
    fun incomeIsDetectedByKeywords() {
        assertEquals(ParsedVoice("income", BigDecimal("80000.00"), "зарплату"), voice("получил зарплату 80 тысяч"))
        assertEquals("income", voice("пришел кэшбэк 350").kind)
    }

    /** Множители тысяч и миллионов. */
    @Test
    fun thousandAndMillionMultipliers() {
        assertEquals(BigDecimal("5000.00"), voice("5к на бензин").amount)
        assertEquals("бензин", voice("5к на бензин").description)
        assertEquals(BigDecimal("2000000.00"), voice("купил машину за 2 млн").amount)
        assertEquals(BigDecimal("3500.00"), voice("3,5 тыс. за куртку").amount)
    }

    /** Сумма словами. */
    @Test
    fun amountInWords() {
        assertEquals(ParsedVoice("expense", BigDecimal("1500.00"), "продукты"), voice("Купила продукты за полторы тысячи рублей"))
        assertEquals(ParsedVoice("expense", BigDecimal("2350.00"), "такси"), voice("две тысячи триста пятьдесят на такси"))
        assertEquals(BigDecimal("1000.00"), voice("тысяча на подарок").amount)
    }

    /** Фраза без суммы. */
    @Test
    fun phraseWithoutAmount() {
        assertEquals(ParsedVoice("expense", null, "кофе"), voice("кофе"))
    }

    /** Числительные преобразуются в число. */
    @Test
    fun numeralsAreConvertedToNumber() {
        assertEquals(BigDecimal("125000.00") to 4, VoiceParser.wordsToNumber(listOf("сто", "двадцать", "пять", "тысяч", "рублей")))
        assertEquals(null to 0, VoiceParser.wordsToNumber(listOf("рублей")))
    }

    // ------------------------------------------------------------ чек
    /** Из чека выделяются итог дата и торговая точка. */
    @Test
    fun receiptTotalDateAndMerchant() {
        val text = """
            ООО "АГРОТОРГ"
            КАССОВЫЙ ЧЕК
            Хлеб 45.90
            Молоко 89.00
            ИТОГ 134.90
            20.11.2026 15:30
            СПАСИБО ЗА ПОКУПКУ
        """.trimIndent()
        val parsed = ReceiptParser.parse(text)
        assertEquals(BigDecimal("134.90"), parsed.amount)
        assertEquals(Dates.parseInstant("2026-11-20T15:30:00Z"), parsed.occurredAt)
        assertEquals("АГРОТОРГ", parsed.merchant)
    }

    /** Строка QR-кода фискального чека имеет приоритет. */
    @Test
    fun fiscalQrLineHasPriority() {
        val parsed = ReceiptParser.parse("Магазин\nИТОГ 10.00\nt=20261120T1530&s=1234.56&fn=7281440500123456")
        assertEquals(BigDecimal("1234.56"), parsed.amount)
        assertEquals(Dates.parseInstant("2026-11-20T15:30:00Z"), parsed.occurredAt)
    }

    /** Итог с разделителями разрядов и запятой. */
    @Test
    fun totalWithGroupSeparatorsAndComma() {
        assertEquals(BigDecimal("1234.56"), ReceiptParser.parse("ИТОГО: 1 234,56").amount)
        assertEquals(BigDecimal("990.00"), ReceiptParser.parse("К ОПЛАТЕ = 990.00\nБезналичными 990.00").amount)
    }

    /** Без строки итога берется наибольшая сумма. */
    @Test
    fun largestAmountIsUsedWithoutTotalLine() {
        val parsed = ReceiptParser.parse("Coffee Shop\n100.00\n250.50\n19.99")
        assertEquals(BigDecimal("250.50"), parsed.amount)
        assertEquals("Coffee Shop", parsed.merchant)
        assertNull(parsed.occurredAt)
    }

    /** Пустой или нераспознанный текст. */
    @Test
    fun emptyOrUnrecognizedText() {
        val parsed = ReceiptParser.parse("")
        assertNull(parsed.amount)
        assertEquals("", parsed.merchant)
        assertNull(ReceiptParser.parse("32.13.2026").occurredAt)
    }

    // ------------------------------------------------------------ подбор категории
    private val categories = SystemCategories.all()
    private val supermarkets = SystemCategories.id("Продукты/Супермаркеты")

    private fun history(merchant: String, category: String, at: Long, kind: String = "expense") = TransactionEntity(
        id = "t$at", kind = kind, amount = BigDecimal.ONE, accountId = "a", categoryId = category, occurredAt = at, merchant = merchant,
    )

    /** Категория подбирается по последней операции с тем же описанием. */
    @Test
    fun categoryIsSuggestedFromLastSameDescription() {
        val items = listOf(
            history("Пятерочка", SystemCategories.id("Прочее"), at = 1),
            history("ПЯТЁРОЧКА!", supermarkets, at = 2),
        )
        assertEquals(supermarkets, CategorySuggester.suggest("пятерочка", "expense", items, categories))
        assertNull(CategorySuggester.suggest("пятерочка", "income", items, categories))
    }

    /** Без истории категория подбирается по названию. */
    @Test
    fun categoryIsSuggestedByNameWithoutHistory() {
        assertEquals(SystemCategories.id("Кафе"), CategorySuggester.suggest("кафе", "expense", emptyList(), categories))
        assertEquals(SystemCategories.id("Транспорт/Такси"), CategorySuggester.suggest("такси домой", "expense", emptyList(), categories))
        assertEquals(SystemCategories.id("Зарплата"), CategorySuggester.suggest("Зарплата", "income", emptyList(), categories))
        assertNull(CategorySuggester.suggest("неизвестный магазин", "expense", emptyList(), categories))
        assertNull(CategorySuggester.suggest("  ", "expense", emptyList(), categories))
    }
}
