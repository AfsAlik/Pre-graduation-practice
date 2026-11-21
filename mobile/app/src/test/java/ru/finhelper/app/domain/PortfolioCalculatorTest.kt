package ru.finhelper.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.finhelper.app.DemoData
import ru.finhelper.app.data.local.TradeEntity
import java.math.BigDecimal

/** Контрольный расчет инвестиционного портфеля (п. 4.2.1.3 в, е; 4.3.1.1 ТЗ). */
class PortfolioCalculatorTest {
    private fun trade(id: String, side: String, quantity: String, price: String, fee: String = "0", at: Long, ticker: String = "AAA") =
        TradeEntity(id, "stock", ticker, "Актив", side, BigDecimal(quantity), BigDecimal(price), BigDecimal(fee), "RUB", tradedAt = at, createdAt = at)

    private val trades = listOf(
        trade("1", "buy", "10", "100", "10", at = 1),
        trade("2", "buy", "10", "120", at = 2),
        trade("3", "sell", "5", "150", "5", at = 3),
    )

    private fun assertDecimal(expected: String, actual: BigDecimal) =
        assertEquals("ожидалось $expected, получено $actual", 0, BigDecimal(expected).compareTo(actual.setScale(4, java.math.RoundingMode.HALF_UP)))

    /** Метод средней себестоимости. */
    @Test
    fun averageCostMethod() {
        val state = PortfolioCalculator.position(trades, PortfolioCalculator.METHOD_AVERAGE)
        // Себестоимость 20 шт. = 1010 + 1200 = 2210; продано 5 шт. по 110,5 = 552,5
        assertDecimal("15", state.quantity)
        assertDecimal("1657.5", state.invested)
        assertDecimal("110.5", state.averagePrice)
        assertDecimal("15", state.fees)
        // Выручка 750 − 5 = 745; результат 745 − 552,5 = 192,5
        assertDecimal("192.5", state.realized)
    }

    /** Метод FIFO списывает первые купленные лоты. */
    @Test
    fun fifoMethodUsesFirstLots() {
        val state = PortfolioCalculator.position(trades, PortfolioCalculator.METHOD_FIFO)
        // Проданы 5 шт. из первого лота по 101 = 505; результат 745 − 505 = 240
        assertDecimal("15", state.quantity)
        assertDecimal("1705", state.invested)
        assertDecimal("240", state.realized)
        assertDecimal("113.6667", state.averagePrice)
    }

    /** Продажа сверх остатка ограничивается размером позиции. */
    @Test
    fun sellingMoreThanHeldIsLimitedToPosition() {
        val state = PortfolioCalculator.position(listOf(trade("1", "buy", "3", "10", at = 1), trade("2", "sell", "5", "12", at = 2)))
        assertEquals(0, state.quantity.signum())
        assertDecimal("6", state.realized)
        assertEquals(0, state.averagePrice.signum())
    }

    /** Сделки применяются в порядке дат независимо от порядка в списке. */
    @Test
    fun tradesAreAppliedInDateOrder() {
        assertEquals(PortfolioCalculator.position(trades), PortfolioCalculator.position(trades.reversed()))
    }

    /** Изменение сделки запрещено через 7 дней после фиксации. */
    @Test
    fun tradeIsLockedAfter7Days() {
        val day = 24 * 60 * 60 * 1000L
        val created = trade("1", "buy", "1", "1", at = 1_000_000)
        assertFalse(PortfolioCalculator.isLocked(created, created.createdAt + 7 * day))
        assertTrue(PortfolioCalculator.isLocked(created, created.createdAt + 7 * day + 1))
    }

    /** Стоимость доходность и структура портфеля демонстрационного набора. */
    @Test
    fun demoPortfolioValueReturnAndStructure() {
        val portfolio = PortfolioCalculator.portfolio(DemoData.trades, DemoData.prices, "average", DemoData.converter, "RUB")
        assertEquals(5, portfolio.positions.size)
        assertEquals(BigDecimal("187806.15"), portfolio.totalInvested)
        assertEquals(BigDecimal("201722.00"), portfolio.totalValue)
        assertEquals(BigDecimal("13915.85"), portfolio.totalProfit)
        assertEquals(7.41, portfolio.totalProfitPercent, 0.0)
        assertEquals(mapOf("crypto" to 50.9, "stock" to 34.5, "bond" to 9.4, "etf" to 5.2), portfolio.structure)
        val sber = portfolio.positions.first { it.ticker == "SBER" }
        assertEquals(BigDecimal("45375.00"), sber.currentValue)
        assertEquals(BigDecimal("4759.70"), sber.profit)
        assertEquals(11.72, sber.profitPercent, 0.0)
        assertDecimal("40615.30", sber.state.invested)
    }

    /** Без котировки позиция оценивается по цене покупки. */
    @Test
    fun positionWithoutQuoteIsValuedAtPurchasePrice() {
        val portfolio = PortfolioCalculator.portfolio(listOf(trade("1", "buy", "10", "50", at = 1)), emptyMap(), "average", CurrencyConverter.FALLBACK, "RUB")
        val position = portfolio.positions.single()
        assertNull(position.currentPrice)
        assertEquals(BigDecimal("500.00"), position.currentValue)
        assertEquals(0, position.profit.signum())
    }

    /** Позиция в валюте пересчитывается в базовую валюту. */
    @Test
    fun foreignCurrencyPositionIsConvertedToBase() {
        val usd = trade("1", "buy", "2", "100", at = 1).copy(currency = "USD")
        val portfolio = PortfolioCalculator.portfolio(listOf(usd), mapOf("AAA" to BigDecimal("110")), "average", CurrencyConverter.FALLBACK, "RUB")
        // 2 × 110 USD × 90 = 19 800 RUB; вложено 200 USD × 90 = 18 000 RUB
        assertEquals(BigDecimal("19800.00"), portfolio.totalValue)
        assertEquals(BigDecimal("1800.00"), portfolio.totalProfit)
        assertEquals(10.0, portfolio.totalProfitPercent, 0.0)
    }
}
