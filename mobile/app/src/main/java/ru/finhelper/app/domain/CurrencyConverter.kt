package ru.finhelper.app.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Пересчет сумм между валютами по сохраненным курсам к рублю (п. 4.2.1.1 ж ТЗ).
 *
 * @property rates курс валюты к рублю за единицу, например USD -> 90.00
 * @property asOf дата актуальности курсов (ГГГГ-ММ-ДД) либо null, если курсы еще не загружались
 */
class CurrencyConverter(private val rates: Map<String, BigDecimal> = emptyMap(), val asOf: String? = null) {

    /** Валюты, для которых известен курс. */
    val currencies: List<String> get() = listOf(BASE) + rates.keys.filter { it != BASE }.sorted()

    private fun rate(currency: String): BigDecimal? = if (currency == BASE) BigDecimal.ONE else rates[currency]

    /**
     * Пересчитывает сумму через рубль. Если курс неизвестен, сумма возвращается
     * без изменения (так же поступает серверная часть).
     */
    fun convert(amount: BigDecimal, from: String, to: String): BigDecimal {
        if (from == to) return amount.setScale(2, RoundingMode.HALF_UP)
        val fromRate = rate(from)
        val toRate = rate(to)
        if (fromRate == null || toRate == null || toRate.signum() == 0) return amount.setScale(2, RoundingMode.HALF_UP)
        return amount.multiply(fromRate).divide(toRate, MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_UP)
    }

    companion object {
        const val BASE = "RUB"

        /** Курсы по умолчанию для первого запуска без сети; заменяются курсами сервера при синхронизации. */
        val FALLBACK = CurrencyConverter(
            mapOf(
                "USD" to BigDecimal("90.00"), "EUR" to BigDecimal("98.00"), "CNY" to BigDecimal("12.50"),
                "KZT" to BigDecimal("0.18"), "BYN" to BigDecimal("27.50"), "GBP" to BigDecimal("114.00"),
            ),
        )
    }
}
