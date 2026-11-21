package ru.finhelper.app.ui.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finhelper.app.R
import ru.finhelper.app.data.local.TradeEntity
import ru.finhelper.app.data.remote.AssetDto
import ru.finhelper.app.data.remote.ValuePointDto
import ru.finhelper.app.data.remote.userMessage
import ru.finhelper.app.data.repo.FinanceRepository
import ru.finhelper.app.data.repo.FinanceSnapshot
import ru.finhelper.app.data.repo.InvestmentRepository
import ru.finhelper.app.data.repo.ValidationError
import ru.finhelper.app.di.AppMessages
import ru.finhelper.app.domain.Portfolio
import ru.finhelper.app.domain.PortfolioCalculator
import ru.finhelper.app.domain.Position

/** Данные формы «Добавить сделку». */
data class TradeInput(
    val id: String? = null,
    val assetType: String = "stock",
    val ticker: String = "",
    val name: String = "",
    val side: String = "buy",
    val quantity: BigDecimal = BigDecimal.ZERO,
    val price: BigDecimal = BigDecimal.ZERO,
    val fee: BigDecimal = BigDecimal.ZERO,
    val currency: String = "RUB",
    val tradedAt: Long = 0,
)

/** Состояние экрана «Инвестиционный портфель». */
data class InvestmentsUiState(
    val loading: Boolean = true,
    val portfolio: Portfolio? = null,
    val currency: String = "RUB",
    val currencies: List<String> = listOf("RUB"),
    /** Момент получения котировок с сервера, мс; 0 – котировки не загружались. */
    val pricesAt: Long = 0,
    /** Выполняется обновление котировок. */
    val refreshing: Boolean = false,
    /** Период графика динамики: day, month или year. */
    val dynamicsPeriod: String = "month",
    val dynamics: List<ValuePointDto> = emptyList(),
    /** Пояснение, почему показаны сохраненные данные (нет связи с сервером). */
    val offlineNote: String? = null,
)

/** Сделка актива и признак запрета изменения количества и цены (п. 4.2.1.3 е ТЗ). */
data class TradeRow(val trade: TradeEntity, val locked: Boolean)

/** Состояние экрана детальной аналитики по активу. */
data class AssetUiState(
    val ticker: String = "",
    val loading: Boolean = true,
    val position: Position? = null,
    val details: AssetDto? = null,
    val trades: List<TradeRow> = emptyList(),
    val baseCurrency: String = "RUB",
    val currencies: List<String> = listOf("RUB"),
    val offlineNote: String? = null,
)

fun buildPortfolio(snapshot: FinanceSnapshot): Portfolio = PortfolioCalculator.portfolio(
    snapshot.trades, snapshot.prices, snapshot.profile.costingMethod, snapshot.converter, snapshot.baseCurrency,
)

fun buildAssetState(snapshot: FinanceSnapshot, ticker: String, now: Long, details: AssetDto? = null, offlineNote: String? = null) = AssetUiState(
    ticker = ticker,
    loading = !snapshot.loaded,
    position = buildPortfolio(snapshot).positions.firstOrNull { it.ticker == ticker },
    details = details,
    trades = snapshot.trades.filter { it.ticker.equals(ticker, ignoreCase = true) }.map { TradeRow(it, PortfolioCalculator.isLocked(it, now)) },
    baseCurrency = snapshot.baseCurrency,
    currencies = snapshot.converter.currencies,
    offlineNote = offlineNote,
)

/**
 * Инвестиционный портфель (п. 4.2.1.3 ТЗ). Сделки хранятся на устройстве, позиции
 * и доходность рассчитываются локально по последним известным котировкам. При
 * открытии портфеля котировки обновляются с сервера (п. 4.2.2.15 ТЗ).
 */
class InvestmentsViewModel(
    private val finance: FinanceRepository,
    private val investments: InvestmentRepository,
    private val messages: AppMessages,
) : ViewModel() {
    private data class Remote(
        val refreshing: Boolean = false,
        val period: String = "month",
        val dynamics: List<ValuePointDto> = emptyList(),
        val offlineNote: String? = null,
    )

    private val remote = MutableStateFlow(Remote())

    val state: StateFlow<InvestmentsUiState> = combine(finance.snapshot, remote) { snapshot, extra ->
        InvestmentsUiState(
            loading = !snapshot.loaded,
            portfolio = buildPortfolio(snapshot),
            currency = snapshot.baseCurrency,
            currencies = snapshot.converter.currencies,
            pricesAt = snapshot.pricesAt,
            refreshing = extra.refreshing,
            dynamicsPeriod = extra.period,
            dynamics = extra.dynamics,
            offlineNote = extra.offlineNote,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvestmentsUiState())

    init {
        refresh()
    }

    /** Обновляет котировки и график динамики; без сети остаются сохраненные данные. */
    fun refresh() {
        remote.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val quotes = investments.refreshQuotes()
            remote.update { it.copy(refreshing = false, offlineNote = quotes.getOrNull()?.error ?: quotes.exceptionOrNull()?.userMessage()) }
            loadDynamics(remote.value.period)
        }
    }

    fun setPeriod(period: String) {
        remote.update { it.copy(period = period) }
        viewModelScope.launch { loadDynamics(period) }
    }

    private suspend fun loadDynamics(period: String) {
        val points = investments.dynamics(period).getOrNull()?.value?.points.orEmpty()
        remote.update { if (it.period == period) it.copy(dynamics = points) else it }
    }

    fun saveTrade(input: TradeInput) {
        viewModelScope.launch {
            try {
                finance.saveTrade(
                    input.id, input.assetType, input.ticker, input.name, input.side,
                    input.quantity, input.price, input.fee, input.currency, input.tradedAt,
                )
                messages.show(if (input.id == null) R.string.trade_added else R.string.trade_saved)
                refresh()
            } catch (error: ValidationError) {
                messages.show(error.message.orEmpty())
            }
        }
    }

    fun deleteTrade(id: String) {
        viewModelScope.launch {
            try {
                finance.deleteTrade(id)
                messages.show(R.string.trade_deleted)
            } catch (error: ValidationError) {
                messages.show(error.message.orEmpty())
            }
        }
    }
}

/** Детальная аналитика по активу: история цен, доходность за периоды, комиссии и сделки (п. 4.2.1.3 д ТЗ). */
class AssetViewModel(
    private val ticker: String,
    finance: FinanceRepository,
    private val investments: InvestmentRepository,
) : ViewModel() {
    private data class Remote(val details: AssetDto? = null, val offlineNote: String? = null)

    private val remote = MutableStateFlow(Remote())

    val state: StateFlow<AssetUiState> = combine(finance.snapshot, remote) { snapshot, extra ->
        buildAssetState(snapshot, ticker, System.currentTimeMillis(), extra.details, extra.offlineNote)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetUiState(ticker = ticker))

    init {
        viewModelScope.launch {
            val result = investments.asset(ticker)
            remote.value = Remote(result.getOrNull()?.value, result.getOrNull()?.error ?: result.exceptionOrNull()?.userMessage())
        }
    }
}
