package ru.finhelper.app.ui.admin

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.remote.MlModelDto
import ru.finhelper.app.data.remote.QualityDto
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.BarChart
import ru.finhelper.app.ui.components.BarGroup
import ru.finhelper.app.ui.components.ChartLegend
import ru.finhelper.app.ui.components.ChartPalette
import ru.finhelper.app.ui.components.ChartSlice
import ru.finhelper.app.ui.components.ErrorState
import ru.finhelper.app.ui.components.LabeledValue
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.PercentBar
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.StatusChip
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.navigation.Routes

/** Требуемая доля положительных оценок рекомендаций (п. 4.3.1.5 ТЗ). */
private const val FEEDBACK_TARGET = 0.75

/**
 * Меню «Администрирование»: отдельное меню административных разделов. Аналитику
 * доступны разделы качества и моделей, администратору – все разделы (п. 4.4.5.9 ТЗ).
 */
@Composable
fun AdminMenuScreen(isAdmin: Boolean, onBack: () -> Unit = {}, onNavigate: (String) -> Unit = {}) {
    AppScaffold(title = stringResource(R.string.admin_title), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            AdminMenuItem(Icons.Filled.Insights, R.string.admin_ai_title, R.string.admin_ai_hint) { onNavigate(Routes.ADMIN_AI) }
            AdminMenuItem(Icons.Filled.ModelTraining, R.string.admin_models_title, R.string.admin_models_hint) { onNavigate(Routes.ADMIN_MODELS) }
            if (isAdmin) {
                HorizontalDivider()
                AdminMenuItem(Icons.Filled.People, R.string.admin_users_title, R.string.admin_users_hint) { onNavigate(Routes.ADMIN_USERS) }
                AdminMenuItem(Icons.AutoMirrored.Filled.ListAlt, R.string.admin_logs_title, R.string.admin_logs_hint) { onNavigate(Routes.ADMIN_LOGS) }
                AdminMenuItem(Icons.Filled.MonitorHeart, R.string.admin_monitoring_title, R.string.admin_monitoring_hint) {
                    onNavigate(Routes.ADMIN_MONITORING)
                }
                AdminMenuItem(Icons.Filled.Settings, R.string.admin_settings_title, R.string.admin_settings_hint) { onNavigate(Routes.ADMIN_SETTINGS) }
            }
        }
    }
}

@Composable
private fun AdminMenuItem(icon: ImageVector, title: Int, hint: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * Экран «Аналитика ИИ»: показатели качества автоматической категоризации
 * (accuracy, precision, recall, F1), ошибка прогноза, доля положительных оценок
 * рекомендаций и их динамика за три месяца (п. 4.2.1.5 ж ТЗ).
 */
@Composable
fun AiAnalyticsScreen(state: AiAnalyticsUiState, onBack: () -> Unit = {}, onLoad: () -> Unit = {}) {
    LaunchedEffect(Unit) { onLoad() }
    AppScaffold(
        title = stringResource(R.string.admin_ai_title),
        onBack = onBack,
        actions = { TooltipIconButton(Icons.Filled.Refresh, stringResource(R.string.action_refresh), onLoad) },
    ) { padding ->
        val quality = state.quality
        when {
            quality != null -> AiAnalyticsContent(quality, Modifier.padding(padding))
            state.loading -> LoadingBox(Modifier.padding(padding))
            else -> ErrorState(state.error ?: stringResource(R.string.online_only), onLoad, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AiAnalyticsContent(quality: QualityDto, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        val model = quality.model
        SectionCard(title = stringResource(R.string.ai_categorization)) {
            if (model == null) {
                Text(stringResource(R.string.ai_no_model), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(R.string.ai_model_version, model.version, model.samples),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Metric("Accuracy", model.accuracy)
                Metric("Precision", model.precision)
                Metric("Recall", model.recall)
                Metric("F1-score", model.f1)
                Spacer(Modifier.size(8.dp))
                val enough = model.accuracy >= quality.minAccuracy
                StatusChip(
                    stringResource(if (enough) R.string.ai_accuracy_ok else R.string.ai_accuracy_low, Money.formatPercent(quality.minAccuracy * 100)),
                    if (enough) Tone.POSITIVE else Tone.NEGATIVE,
                    icon = if (enough) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                )
            }
        }
        SectionCard(title = stringResource(R.string.ai_forecast_and_feedback)) {
            LabeledValue(
                stringResource(R.string.ai_forecast_mape),
                quality.forecastMape?.let { Money.formatPercent(it) } ?: stringResource(R.string.asset_no_data),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.ai_positive_feedback), style = MaterialTheme.typography.bodyMedium)
            val enough = quality.positiveFeedbackShare >= FEEDBACK_TARGET
            PercentBar(
                quality.positiveFeedbackShare * 100, if (enough) Tone.POSITIVE else Tone.WARNING,
                description = stringResource(R.string.ai_positive_feedback),
            )
            Text(
                stringResource(R.string.ai_feedback_target, Money.formatPercent(FEEDBACK_TARGET * 100)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            LabeledValue(stringResource(R.string.ai_rated), quality.ratedRecommendations.toString())
            LabeledValue(stringResource(R.string.ai_applied), quality.appliedRecommendations.toString())
            LabeledValue(stringResource(R.string.ai_training_samples), quality.trainingSamples.toString())
        }
        SectionCard(title = stringResource(R.string.ai_history)) {
            if (quality.history.isEmpty()) {
                Text(stringResource(R.string.asset_no_data), style = MaterialTheme.typography.bodyMedium)
            } else {
                BarChart(
                    groups = quality.history.map { point ->
                        val label = runCatching { Dates.monthShort(YearMonth.parse(point.month)) }.getOrDefault(point.month)
                        BarGroup(label, listOf(point.accuracy * 100, point.f1 * 100, (point.positiveFeedbackShare ?: 0.0) * 100))
                    },
                    colors = ChartPalette.take(3),
                    description = stringResource(R.string.ai_history_description),
                    maxLabel = "100 %",
                )
                Spacer(Modifier.size(4.dp))
                val last = quality.history.last()
                ChartLegend(
                    listOf(
                        ChartSlice("Accuracy", 0.0, ChartPalette[0], Money.formatPercent(last.accuracy * 100)),
                        ChartSlice("F1-score", 0.0, ChartPalette[1], Money.formatPercent(last.f1 * 100)),
                        ChartSlice(
                            stringResource(R.string.ai_positive_feedback), 0.0, ChartPalette[2],
                            Money.formatPercent((last.positiveFeedbackShare ?: 0.0) * 100),
                        ),
                    )
                )
                Spacer(Modifier.size(8.dp))
                quality.history.forEach { point ->
                    val month = runCatching { Dates.monthTitle(YearMonth.parse(point.month)) }.getOrDefault(point.month)
                    LabeledValue(
                        month,
                        stringResource(
                            R.string.ai_history_row, Money.formatPercent(point.accuracy * 100), Money.formatPercent(point.precision * 100),
                            Money.formatPercent(point.recall * 100), Money.formatPercent(point.f1 * 100),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(name: String, value: Double) {
    Text(name, style = MaterialTheme.typography.bodyMedium)
    PercentBar(value * 100, Tone.NEUTRAL, description = name)
}

/**
 * Экран «Управление моделями ML»: сведения о накопленных данных, список версий
 * модели, запуск нового обучения и выбор активной версии (п. 4.2.1.5 з ТЗ).
 */
@Composable
fun ModelsScreen(
    state: ModelsUiState,
    onBack: () -> Unit = {},
    onLoad: () -> Unit = {},
    onTrain: () -> Unit = {},
    onActivate: (String) -> Unit = {},
) {
    LaunchedEffect(Unit) { onLoad() }
    val training = state.models.any { it.status == AdminViewModel.STATUS_TRAINING }
    AppScaffold(
        title = stringResource(R.string.admin_models_title),
        onBack = onBack,
        actions = { TooltipIconButton(Icons.Filled.Refresh, stringResource(R.string.action_refresh), onLoad) },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.error != null && state.models.isEmpty() -> ErrorState(state.error, onLoad, Modifier.padding(padding))
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item {
                    SectionCard(title = stringResource(R.string.models_data)) {
                        LabeledValue(stringResource(R.string.ai_training_samples), (state.trainingData?.total ?: 0).toString())
                        LabeledValue(stringResource(R.string.models_categories), (state.trainingData?.byCategory?.size ?: 0).toString())
                        Spacer(Modifier.size(8.dp))
                        if (training) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                stringResource(R.string.models_training_note),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Button(onClick = onTrain, enabled = !training && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.models_train))
                        }
                    }
                }
                item { Text(stringResource(R.string.models_versions), style = MaterialTheme.typography.titleMedium) }
                items(state.models, key = { it.id }) { model -> ModelCard(model, enabled = !state.busy, onActivate) }
            }
        }
    }
}

@Composable
private fun ModelCard(model: MlModelDto, enabled: Boolean, onActivate: (String) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.models_version, model.version), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                model.isActive -> StatusChip(stringResource(R.string.models_status_active), Tone.POSITIVE, icon = Icons.Filled.CheckCircle)
                model.status == AdminViewModel.STATUS_TRAINING -> StatusChip(stringResource(R.string.models_status_training), Tone.WARNING, icon = Icons.Filled.Schedule)
                model.status == "failed" -> StatusChip(stringResource(R.string.models_status_failed), Tone.NEGATIVE, icon = Icons.Filled.Error)
                else -> StatusChip(stringResource(R.string.models_status_ready), Tone.NEUTRAL)
            }
        }
        Spacer(Modifier.size(4.dp))
        LabeledValue(
            stringResource(R.string.models_trained_at),
            model.trainedAt?.let(Dates::formatDateTime) ?: stringResource(R.string.asset_no_data),
        )
        LabeledValue("Accuracy", Money.formatPercent(model.accuracy * 100))
        LabeledValue("F1-score", Money.formatPercent(model.f1 * 100))
        LabeledValue(stringResource(R.string.ai_training_samples), model.samples.toString())
        if (model.error.isNotBlank()) Text(model.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (model.status == "ready" && !model.isActive) {
            OutlinedButton(onClick = { onActivate(model.id) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.models_activate))
            }
        }
    }
}
