package ru.finhelper.app.ui.recommendations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.remote.ForecastDto
import ru.finhelper.app.data.remote.ForecastValueDto
import ru.finhelper.app.data.remote.RecommendationDto
import ru.finhelper.app.data.remote.RiskDto
import ru.finhelper.app.data.remote.userMessage
import ru.finhelper.app.data.repo.InsightsRepository
import ru.finhelper.app.data.sync.SyncManager
import ru.finhelper.app.di.AppMessages
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.EmptyState
import ru.finhelper.app.ui.components.ErrorState
import ru.finhelper.app.ui.components.LabeledValue
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.NoticeBanner
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.StatusChip
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.components.toneColor

/** Состояние экрана «Рекомендации». */
data class RecommendationsUiState(
    val loading: Boolean = true,
    val items: List<RecommendationDto> = emptyList(),
    val currency: String = "RUB",
    val forecast: ForecastDto? = null,
    val risks: List<RiskDto> = emptyList(),
    /** Момент, на который сохранены показанные данные, если связи с сервером нет. */
    val cachedAt: Long? = null,
    /** Ошибка загрузки при отсутствии сохраненных данных. */
    val error: String? = null,
    /** Рекомендация, по которой выполняется запрос. */
    val busyId: String? = null,
)

/**
 * Интеллектуальные рекомендации, прогноз и риски (п. 4.2.1.5 ТЗ). Формируются
 * сервером; последняя загруженная версия сохраняется для просмотра без сети.
 */
class RecommendationsViewModel(
    private val insights: InsightsRepository,
    private val syncManager: SyncManager,
    private val messages: AppMessages,
) : ViewModel() {
    private val uiState = MutableStateFlow(RecommendationsUiState())
    val state: StateFlow<RecommendationsUiState> = uiState

    init {
        load()
    }

    fun load() {
        uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val recommendations = insights.recommendations()
            val forecast = insights.forecast().getOrNull()
            val risks = insights.risks().getOrNull()
            val cached = recommendations.getOrNull()
            uiState.value = RecommendationsUiState(
                loading = false,
                items = cached?.value?.items.orEmpty(),
                currency = cached?.value?.currency ?: "RUB",
                forecast = forecast?.value,
                risks = risks?.value?.items.orEmpty(),
                cachedAt = cached?.takeIf { !it.fresh }?.savedAt,
                error = recommendations.exceptionOrNull()?.userMessage(),
            )
        }
    }

    /** Явная обратная связь: оценка «Полезно» / «Не полезно». */
    fun rate(id: String, useful: Boolean) = request(id) {
        insights.feedback(id, useful).onSuccess { messages.show(R.string.recommendation_rated) }
    }

    /** Применение рекомендации; созданный сервером бюджет загружается синхронизацией. */
    fun apply(id: String) = request(id) {
        insights.apply(id).onSuccess {
            syncManager.syncNow()
            messages.show(R.string.recommendation_applied)
        }
    }

    private fun request(id: String, call: suspend () -> Result<RecommendationDto>) {
        uiState.update { it.copy(busyId = id) }
        viewModelScope.launch {
            call().onSuccess { updated ->
                uiState.update { state -> state.copy(items = state.items.map { if (it.id == id) updated else it }) }
            }.onFailure { messages.show(it.userMessage()) }
            uiState.update { it.copy(busyId = null) }
        }
    }
}

@Composable
fun RecommendationsRoute(viewModel: RecommendationsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RecommendationsScreen(state, onBack = onBack, onRefresh = viewModel::load, onRate = viewModel::rate, onApply = viewModel::apply)
}

/**
 * Экран «Рекомендации»: прогноз на следующий месяц с интервалом уверенности,
 * предупреждения о финансовых рисках и карточки рекомендаций с пояснением,
 * оценкой экономии и кнопками оценки и применения (п. 4.4.5.32 ТЗ).
 */
@Composable
fun RecommendationsScreen(
    state: RecommendationsUiState,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRate: (String, Boolean) -> Unit = { _, _ -> },
    onApply: (String) -> Unit = {},
) {
    AppScaffold(
        title = stringResource(R.string.recommendations_title),
        onBack = onBack,
        actions = { TooltipIconButton(Icons.Filled.Refresh, stringResource(R.string.action_refresh), onRefresh) },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding), stringResource(R.string.recommendations_loading))
            state.error != null && state.items.isEmpty() -> ErrorState(state.error, onRefresh, Modifier.padding(padding))
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                state.cachedAt?.let { item { NoticeBanner(stringResource(R.string.cached_notice, Dates.formatDateTime(it))) } }
                state.forecast?.let { forecast -> item { ForecastCard(forecast) } }
                items(state.risks, key = { "risk-${it.id}" }) { risk -> RiskCard(risk) }
                if (state.items.isEmpty()) {
                    item { EmptyState(stringResource(R.string.recommendations_empty), icon = Icons.Filled.Lightbulb) }
                }
                items(state.items, key = { it.id }) { item ->
                    RecommendationCard(item, state.currency, enabled = state.busyId == null && state.cachedAt == null, onRate, onApply)
                }
            }
        }
    }
}

@Composable
private fun ForecastCard(forecast: ForecastDto) {
    val month = runCatching { Dates.monthTitle(YearMonth.parse(forecast.month)) }.getOrDefault(forecast.month)
    SectionCard(title = stringResource(R.string.forecast_title, month)) {
        ForecastRow(stringResource(R.string.dashboard_expense), forecast.expense, forecast.currency)
        ForecastRow(stringResource(R.string.dashboard_income), forecast.income, forecast.currency)
        Text(
            stringResource(R.string.forecast_confidence, (forecast.confidence * 100).toInt()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ForecastRow(label: String, value: ForecastValueDto, currency: String) {
    LabeledValue(label, Money.format(value.forecast.toBigDecimal(), currency, kopecks = false), bold = true)
    Text(
        stringResource(
            R.string.forecast_interval,
            Money.format(value.lower.toBigDecimal(), currency, kopecks = false), Money.format(value.upper.toBigDecimal(), currency, kopecks = false),
        ),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun RiskCard(risk: RiskDto) {
    val critical = risk.severity == "critical"
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (critical) Icons.Filled.Error else Icons.Filled.Warning, contentDescription = null,
                tint = toneColor(if (critical) Tone.NEGATIVE else Tone.WARNING),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(if (critical) R.string.risk_critical else R.string.risk_warning),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(risk.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecommendationCard(
    item: RecommendationDto,
    currency: String,
    enabled: Boolean,
    onRate: (String, Boolean) -> Unit,
    onApply: (String) -> Unit,
) {
    val applied = item.status == "applied"
    SectionCard {
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(item.text, style = MaterialTheme.typography.bodyMedium)
        if (item.explanation.isNotBlank()) {
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.recommendation_explanation, item.explanation),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.estimatedSaving.signum() > 0) {
            Spacer(Modifier.size(8.dp))
            StatusChip(
                stringResource(R.string.recommendation_saving, Money.format(item.estimatedSaving, currency, kopecks = false)),
                Tone.POSITIVE,
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = item.feedback == "useful",
                onClick = { onRate(item.id, true) },
                enabled = enabled,
                label = { Text(stringResource(R.string.recommendation_useful)) },
                leadingIcon = { Icon(Icons.Filled.ThumbUp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            FilterChip(
                selected = item.feedback == "not_useful",
                onClick = { onRate(item.id, false) },
                enabled = enabled,
                label = { Text(stringResource(R.string.recommendation_not_useful)) },
                leadingIcon = { Icon(Icons.Filled.ThumbDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        val actionLabel = (item.action["label"] as? JsonPrimitive)?.content
        when {
            applied -> StatusChip(stringResource(R.string.recommendation_applied_status), Tone.POSITIVE, icon = Icons.Filled.CheckCircle)
            actionLabel != null -> Button(
                onClick = { onApply(item.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(actionLabel) }
        }
    }
}
