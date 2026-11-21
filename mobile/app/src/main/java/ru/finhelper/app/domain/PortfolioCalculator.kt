package ru.finhelper.app.domain

import ru.finhelper.app.data.local.TradeEntity
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.ArrayDeque

/** Состояние позиции по одному активу, рассчитанное по сделкам. */
data class PositionState(
    val quantity: BigDecimal,
    /** Себестоимость остатка с учетом комиссий покупки. */
    val invested: BigDecimal,
    val averagePrice: BigDecimal,
    val fees: BigDecimal,
    /** Реализованный финансовый результат по продажам. */
    val realized: BigDecimal,
)

/** Позиция портфеля с текущей стоимостью и доходностью. */
data class Position(
    val ticker: String,
    val name: String,
    val assetType: String,
    val currency: String,
    val state: PositionState,
    /** Последняя известная рыночная цена; null – котировка еще не получена. */
    val currentPrice: BigDecimal?,
    val currentValue: BigDecimal,
    val currentValueBase: BigDecimal,
    val profit: BigDecimal,
    val profitPercent: Double,
)

/** Портфель в целом: позиции, итоги в базовой валюте и структура по типам активов. */
data class Portfolio(
    val baseCurrency: String,
    val positions: List<Position>,
    val totalInvested: BigDecimal,
    val totalValue: BigDecimal,
    val totalProfit: BigDecimal,
    val totalProfitPercent: Double,
    /** Доля каждого типа активов в стоимости портфеля, %. */
    val structure: Map<String, Double>,
)

/**
 * Расчет инвестиционного портфеля (п. 4.2.1.3 в, 4.3.1.1 ТЗ).
 *
 * Себестоимость определяется методом средней себестоимости либо методом FIFO.
 * Комиссии покупки включаются в себестоимость. Алгоритм совпадает с серверным
 * (services/portfolio.py), поэтому портфель одинаково рассчитывается и без сети.
 */
object PortfolioCalculator {
    const val METHOD_AVERAGE = "average"
    const val METHOD_FIFO = "fifo"

    /** Число дней, в течение которых можно изменить количество и цену сделки (п. 4.2.1.3 е ТЗ). */
    const val LOCK_DAYS = 7L
    private val MC = MathContext.DECIMAL128
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /** Количество и цену сделки нельзя изменить спустя 7 дней после ее фиксации. */
    fun isLocked(trade: TradeEntity, now: Long): Boolean = now - trade.createdAt > LOCK_DAYS * DAY_MILLIS

    /** Рассчитывает позицию по сделкам с одним активом. */
    fun position(trades: List<TradeEntity>, method: String = METHOD_AVERAGE): PositionState {
        var quantity = BigDecimal.ZERO
        var cost = BigDecimal.ZERO
        var fees = BigDecimal.ZERO
        var realized = BigDecimal.ZERO
        val lots = ArrayDeque<Array<BigDecimal>>() // [количество, цена единицы с комиссией]
        for (trade in trades.sortedWith(compareBy({ it.tradedAt }, { it.createdAt }))) {
            fees += trade.fee
            if (trade.side == "buy") {
                val total = trade.quantity * trade.price + trade.fee
                lots.addLast(arrayOf(trade.quantity, total.divide(trade.quantity, MC)))
                quantity += trade.quantity
                cost += total
                continue
            }
            val sellQuantity = trade.quantity.min(quantity)
            if (sellQuantity.signum() <= 0) continue
            val proceeds = sellQuantity * trade.price - trade.fee
            val soldCost = if (method == METHOD_FIFO) {
                var sum = BigDecimal.ZERO
                var left = sellQuantity
                while (left.signum() > 0 && lots.isNotEmpty()) {
                    val lot = lots.first
                    val take = lot[0].min(left)
                    sum += take * lot[1]
                    lot[0] = lot[0] - take
                    left -= take
                    if (lot[0].signum() == 0) lots.removeFirst()
                }
                sum
            } else {
                cost.divide(quantity, MC) * sellQuantity
            }
            realized += proceeds - soldCost
            cost -= soldCost
            quantity -= sellQuantity
        }
        val average = if (quantity.signum() > 0) cost.divide(quantity, MC) else BigDecimal.ZERO
        return PositionState(quantity, cost, average, fees, realized)
    }

    /**
     * Строит портфель по сделкам и последним известным ценам.
     *
     * @param prices рыночная цена по тикеру в валюте позиции; при отсутствии цены
     * позиция оценивается по средней цене покупки
     */
    fun portfolio(
        trades: List<TradeEntity>,
        prices: Map<String, BigDecimal>,
        method: String,
        converter: CurrencyConverter,
        baseCurrency: String,
    ): Portfolio {
        val positions = mutableListOf<Position>()
        var totalInvested = BigDecimal.ZERO
        var totalValue = BigDecimal.ZERO
        val byType = linkedMapOf<String, BigDecimal>()
        val grouped = trades.filter { it.deletedAt == null }.groupBy { it.ticker.uppercase() }.toSortedMap()
        for ((ticker, items) in grouped) {
            val state = position(items, method)
            if (state.quantity.signum() <= 0 && state.realized.signum() == 0) continue
            val last = items.maxBy { it.tradedAt }
            val price = prices[ticker]
            val value = state.quantity * (price ?: state.averagePrice)
            val profit = value - state.invested
            val valueBase = converter.convert(value, last.currency, baseCurrency)
            totalValue += valueBase
            totalInvested += converter.convert(state.invested, last.currency, baseCurrency)
            byType.merge(last.assetType, valueBase, BigDecimal::add)
            positions += Position(
                ticker = ticker,
                name = last.name.ifBlank { ticker },
                assetType = last.assetType,
                currency = last.currency,
                state = state,
                currentPrice = price,
                currentValue = value.setScale(2, RoundingMode.HALF_UP),
                currentValueBase = valueBase,
                profit = profit.setScale(2, RoundingMode.HALF_UP),
                profitPercent = percent(profit, state.invested, 2),
            )
        }
        val totalProfit = totalValue - totalInvested
        return Portfolio(
            baseCurrency = baseCurrency,
            positions = positions,
            totalInvested = totalInvested.setScale(2, RoundingMode.HALF_UP),
            totalValue = totalValue.setScale(2, RoundingMode.HALF_UP),
            totalProfit = totalProfit.setScale(2, RoundingMode.HALF_UP),
            totalProfitPercent = percent(totalProfit, totalInvested, 2),
            structure = byType.mapValues { percent(it.value, totalValue, 1) },
        )
    }

    private fun percent(part: BigDecimal, whole: BigDecimal, scale: Int): Double =
        if (whole.signum() > 0) {
            part.multiply(BigDecimal(100)).divide(whole, MC).setScale(scale, RoundingMode.HALF_UP).toDouble()
        } else {
            0.0
        }

    /** Название типа актива во множественном числе для диаграммы структуры портфеля. */
    fun assetTypeTitle(type: String): String = when (type) {
        "stock" -> "Акции"
        "bond" -> "Облигации"
        "etf" -> "Фонды"
        "currency" -> "Валюта"
        "crypto" -> "Криптовалюты"
        else -> type
    }
}
