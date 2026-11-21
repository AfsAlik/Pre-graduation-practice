package ru.finhelper.app.ui.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.local.TradeEntity
import ru.finhelper.app.domain.Portfolio
import ru.finhelper.app.domain.PortfolioCalculator
import ru.finhelper.app.domain.Position
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.ChartLegend
import ru.finhelper.app.ui.components.ChartPalette
import ru.finhelper.app.ui.components.ChartSlice
import ru.finhelper.app.ui.components.ConfirmDialog
import ru.finhelper.app.ui.components.DonutChart
import ru.finhelper.app.ui.components.EmptyState
import ru.finhelper.app.ui.components.LabeledValue
import ru.finhelper.app.ui.components.LineChart
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.MainTab
import ru.finhelper.app.ui.components.NoticeBanner
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.StatusChip
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.components.toneColor
import java.math.BigDecimal

@Composable
fun InvestmentsRoute(viewModel: InvestmentsViewModel, onOpenAsset: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InvestmentsScreen(
        state = state,
        onOpenAsset = onOpenAsset,
        onPeriodChange = viewModel::setPeriod,
        onRefresh = viewModel::refresh,
        onSaveTrade = viewModel::saveTrade,
    )
}

/** Текст и цвет финансового результата: знак, значок направления и цвет. */
@Composable
fun ProfitText(profit: BigDecimal, percent: Double, currency: String, modifier: Modifier = Modifier) {
    val positive = profit.signum() >= 0
    val tone = if (positive) Tone.POSITIVE else Tone.NEGATIVE
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            if (positive) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
            contentDescription = null, tint = toneColor(tone), modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "${Money.format(profit, currency, signed = true)} (${Money.formatPercent(percent, signed = true)})",
            style = MaterialTheme.typography.bodyMedium, color = toneColor(tone), maxLines = 1,
        )
    }
}

/**
 * Экран «Инвестиционный портфель»: стоимость и доходность портфеля, график
 * динамики за день, месяц или год, структура по типам активов и список позиций.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestmentsScreen(
    state: InvestmentsUiState,
    onOpenAsset: (String) -> Unit = {},
    onPeriodChange: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    onSaveTrade: (TradeInput) -> Unit = {},
    now: Long = System.currentTimeMillis(),
) {
    var adding by remember { mutableStateOf(false) }
    val portfolio = state.portfolio
    AppScaffold(
        title = stringResource(R.string.investments_title),
        tab = MainTab.INVESTMENTS,
        actions = { TooltipIconButton(Icons.Filled.Refresh, stringResource(R.string.investments_refresh), onRefresh) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.trade_add_title)) },
            )
        },
    ) { padding ->
        when {
            state.loading || portfolio == null -> LoadingBox(Modifier.padding(padding))
            portfolio.positions.isEmpty() -> EmptyState(stringResource(R.string.investments_empty), Modifier.padding(padding), Icons.Filled.ShowChart)
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                if (state.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.offlineNote?.let { item { NoticeBanner(stringResource(R.string.investments_offline, it)) } }
                item { SummaryCard(portfolio, state) }
                item {
                    SectionCard(title = stringResource(R.string.investments_dynamics)) {
                        val periods = listOf("day" to R.string.investments_day, "month" to R.string.period_month, "year" to R.string.period_year)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            periods.forEachIndexed { index, (value, title) ->
                                SegmentedButton(
                                    selected = state.dynamicsPeriod == value, onClick = { onPeriodChange(value) },
                                    shape = SegmentedButtonDefaults.itemShape(index, periods.size), modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(stringResource(title)) }
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        if (state.dynamics.isEmpty()) {
                            Text(stringResource(R.string.investments_no_dynamics), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            val values = state.dynamics.map { it.value.toDouble() }
                            LineChart(
                                values = values,
                                labels = state.dynamics.map { Dates.formatShort(Dates.parseDate(it.date)) },
                                description = stringResource(R.string.investments_dynamics_description),
                                fromZero = false,
                                maxLabel = stringResource(R.string.chart_max, Money.format(values.max().toBigDecimal(), state.currency, kopecks = false)),
                                minLabel = stringResource(R.string.chart_min, Money.format(values.min().toBigDecimal(), state.currency, kopecks = false)),
                            )
                        }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.investments_structure)) {
                        val slices = portfolio.structure.entries.mapIndexed { index, (type, share) ->
                            ChartSlice(PortfolioCalculator.assetTypeTitle(type), share, ChartPalette[index % ChartPalette.size], Money.formatPercent(share))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DonutChart(slices, stringResource(R.string.investments_structure_description), diameter = 120.dp)
                            Spacer(Modifier.width(16.dp))
                            ChartLegend(slices, Modifier.weight(1f))
                        }
                    }
                }
                item { Text(stringResource(R.string.investments_positions), style = MaterialTheme.typography.titleMedium) }
                items(portfolio.positions, key = { it.ticker }) { position -> PositionCard(position) { onOpenAsset(position.ticker) } }
                item { Spacer(Modifier.size(88.dp)) }
            }
        }
    }
    if (adding) TradeDialog(null, false, state.currencies, state.currency, now, onSaveTrade, { adding = false })
}

@Composable
private fun SummaryCard(portfolio: Portfolio, state: InvestmentsUiState) {
    SectionCard {
        Text(stringResource(R.string.investments_total_value), style = MaterialTheme.typography.labelLarge)
        Text(Money.format(portfolio.totalValue, portfolio.baseCurrency), style = MaterialTheme.typography.headlineSmall)
        ProfitText(portfolio.totalProfit, portfolio.totalProfitPercent, portfolio.baseCurrency)
        Spacer(Modifier.size(4.dp))
        LabeledValue(stringResource(R.string.investments_invested), Money.format(portfolio.totalInvested, portfolio.baseCurrency))
        Text(
            if (state.pricesAt > 0) {
                stringResource(R.string.investments_quotes_at, Dates.formatDateTime(state.pricesAt))
            } else {
                stringResource(R.string.investments_no_quotes)
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PositionCard(position: Position, onClick: () -> Unit) {
    SectionCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(position.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${position.ticker} · ${PortfolioCalculator.assetTypeTitle(position.assetType)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Money.format(position.currentValue, position.currency), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.size(4.dp))
        LabeledValue(
            stringResource(R.string.position_quantity_price),
            "${Money.formatQuantity(position.state.quantity)} × ${Money.format(position.state.averagePrice, position.currency)}",
        )
        LabeledValue(
            stringResource(R.string.position_current_price),
            position.currentPrice?.let { Money.format(it, position.currency) } ?: stringResource(R.string.position_no_price),
        )
        ProfitText(position.profit, position.profitPercent, position.currency)
    }
}

@Composable
fun AssetRoute(viewModel: AssetViewModel, portfolioViewModel: InvestmentsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AssetScreen(state, onBack = onBack, onSaveTrade = portfolioViewModel::saveTrade, onDeleteTrade = portfolioViewModel::deleteTrade)
}

/**
 * Карточка актива: график исторических цен, доходность за неделю, месяц, квартал
 * и год, сведения о позиции с учетом комиссий и список сделок.
 */
@Composable
fun AssetScreen(
    state: AssetUiState,
    onBack: () -> Unit = {},
    onSaveTrade: (TradeInput) -> Unit = {},
    onDeleteTrade: (String) -> Unit = {},
    now: Long = System.currentTimeMillis(),
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TradeRow?>(null) }
    var deleting by remember { mutableStateOf<TradeEntity?>(null) }
    val position = state.position
    val details = state.details
    val currency = position?.currency ?: state.trades.firstOrNull()?.trade?.currency ?: state.baseCurrency
    AppScaffold(
        title = position?.name ?: state.ticker,
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.trade_add_title)) },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@AppScaffold
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            state.offlineNote?.let { item { NoticeBanner(stringResource(R.string.investments_offline, it)) } }
            item {
                SectionCard(title = stringResource(R.string.asset_price_history)) {
                    val price = details?.currentPrice ?: position?.currentPrice
                    Text(
                        price?.let { Money.format(it, currency) } ?: stringResource(R.string.position_no_price),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    details?.priceAsOf?.let {
                        Text(
                            stringResource(R.string.investments_quotes_at, Dates.formatDateTime(it)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    val history = details?.history.orEmpty()
                    if (history.isEmpty()) {
                        Text(stringResource(R.string.asset_no_history), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val values = history.map { it.price.toDouble() }
                        LineChart(
                            values = values,
                            labels = history.map { Dates.formatShort(Dates.parseDate(it.date)) },
                            description = stringResource(R.string.asset_price_history_description, state.ticker),
                            fromZero = false,
                            maxLabel = stringResource(R.string.chart_max, Money.format(values.max().toBigDecimal(), currency)),
                            minLabel = stringResource(R.string.chart_min, Money.format(values.min().toBigDecimal(), currency)),
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.asset_returns)) {
                    val periods = listOf(
                        "week" to R.string.period_week, "month" to R.string.period_month,
                        "quarter" to R.string.asset_quarter, "year" to R.string.period_year,
                    )
                    periods.forEach { (key, title) ->
                        val value = details?.returns?.get(key)
                        LabeledValue(
                            stringResource(title),
                            value?.let { Money.formatPercent(it, signed = true) } ?: stringResource(R.string.asset_no_data),
                            valueColor = when {
                                value == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                value >= 0 -> toneColor(Tone.POSITIVE)
                                else -> toneColor(Tone.NEGATIVE)
                            },
                        )
                    }
                }
            }
            if (position != null) {
                item {
                    SectionCard(title = stringResource(R.string.asset_position)) {
                        LabeledValue(stringResource(R.string.trade_quantity), Money.formatQuantity(position.state.quantity))
                        LabeledValue(stringResource(R.string.asset_average_price), Money.format(position.state.averagePrice, currency))
                        LabeledValue(stringResource(R.string.investments_invested), Money.format(position.state.invested, currency))
                        LabeledValue(stringResource(R.string.asset_value), Money.format(position.currentValue, currency), bold = true)
                        LabeledValue(stringResource(R.string.asset_fees), Money.format(position.state.fees, currency))
                        LabeledValue(stringResource(R.string.asset_realized), Money.format(position.state.realized, currency, signed = true))
                        ProfitText(position.profit, position.profitPercent, currency)
                    }
                }
            }
            item { Text(stringResource(R.string.asset_trades), style = MaterialTheme.typography.titleMedium) }
            items(state.trades, key = { it.trade.id }) { row ->
                TradeCard(row, onEdit = { editing = row }, onDelete = { deleting = row.trade })
            }
            item { Spacer(Modifier.size(88.dp)) }
        }
    }
    if (adding) {
        TradeDialog(
            null, false, state.currencies, currency, now, onSaveTrade, { adding = false },
            presetTicker = state.ticker, presetName = position?.name.orEmpty(), presetType = position?.assetType ?: "stock",
        )
    }
    editing?.let { row -> TradeDialog(row.trade, row.locked, state.currencies, currency, now, onSaveTrade, { editing = null }) }
    deleting?.let { trade ->
        ConfirmDialog(
            title = stringResource(R.string.trade_delete_title),
            text = stringResource(R.string.trade_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onDeleteTrade(trade.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun TradeCard(row: TradeRow, onEdit: () -> Unit, onDelete: () -> Unit) {
    val trade = row.trade
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (trade.side == "buy") R.string.trade_buy else R.string.trade_sell) + " · " + Dates.format(trade.tradedAt),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${Money.formatQuantity(trade.quantity)} × ${Money.format(trade.price, trade.currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (trade.fee.signum() > 0) {
                    Text(
                        stringResource(R.string.trade_fee_value, Money.format(trade.fee, trade.currency)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TooltipIconButton(Icons.Filled.Edit, stringResource(R.string.action_edit), onEdit)
            // Удаление сделки старше 7 дней недоступно: кнопка отображается неактивной (п. 4.4.5.19 ТЗ)
            TooltipIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), onDelete, enabled = !row.locked)
        }
        if (row.locked) StatusChip(stringResource(R.string.trade_locked_short), Tone.NEUTRAL, icon = Icons.Filled.Lock)
    }
}
