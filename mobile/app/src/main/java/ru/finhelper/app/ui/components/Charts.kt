package ru.finhelper.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Графики и диаграммы рисуются средствами Compose Canvas без сторонних библиотек.
// Каждый график имеет текстовое описание для программ экранного доступа, а числовые
// значения дублируются в подписях и легенде (п. 4.4.5.39 ТЗ).

/** Подписи оси абсцисс: первая, средняя и последняя точки. */
@Composable
private fun AxisLabels(labels: List<String>) {
    if (labels.isEmpty()) return
    val shown = when {
        labels.size <= 6 -> labels
        else -> listOf(labels.first(), labels[labels.size / 2], labels.last())
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        shown.forEachIndexed { index, label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = when {
                    shown.size == 1 -> TextAlign.Center
                    index == 0 -> TextAlign.Start
                    index == shown.lastIndex -> TextAlign.End
                    else -> TextAlign.Center
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun DrawScope.drawGrid(color: Color, lines: Int = 4) {
    for (index in 0..lines) {
        val y = size.height * index / lines
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

/**
 * Линейный график.
 *
 * @param values значения по порядку слева направо
 * @param labels подписи точек по оси абсцисс
 * @param maxLabel подпись наибольшего значения (выводится над графиком)
 */
@Composable
fun LineChart(
    values: List<Double>,
    labels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 140.dp,
    maxLabel: String? = null,
    minLabel: String? = null,
    fromZero: Boolean = true,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth()) {
        if (maxLabel != null) {
            Text(maxLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
            drawGrid(grid)
            if (values.isEmpty()) return@Canvas
            val max = values.max()
            val min = if (fromZero) minOf(0.0, values.min()) else values.min()
            val range = (max - min).takeIf { it > 0 } ?: 1.0
            val step = if (values.size > 1) size.width / (values.size - 1) else 0f
            val points = values.mapIndexed { index, value ->
                Offset(if (values.size > 1) index * step else size.width / 2, (size.height * (1 - (value - min) / range)).toFloat())
            }
            if (points.size == 1) {
                drawCircle(color, radius = 5.dp.toPx(), center = points.first())
                return@Canvas
            }
            val line = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(points.last().x, size.height)
                lineTo(points.first().x, size.height)
                close()
            }
            drawPath(area, color.copy(alpha = 0.12f))
            drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, radius = 4.dp.toPx(), center = points.last())
        }
        if (minLabel != null) {
            Text(minLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AxisLabels(labels)
    }
}

/** Группа столбцов с общей подписью, например доход и расход за один месяц. */
data class BarGroup(val label: String, val values: List<Double>)

/**
 * Столбчатая диаграмма с несколькими рядами.
 * @param colors цвет каждого ряда; число цветов равно числу значений в группе
 */
@Composable
fun BarChart(
    groups: List<BarGroup>,
    colors: List<Color>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    maxLabel: String? = null,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth()) {
        if (maxLabel != null) {
            Text(maxLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
            drawGrid(grid)
            if (groups.isEmpty()) return@Canvas
            val max = groups.maxOf { group -> group.values.maxOrNull() ?: 0.0 }.takeIf { it > 0 } ?: 1.0
            val groupWidth = size.width / groups.size
            val series = groups.maxOf { it.values.size }.coerceAtLeast(1)
            val gap = groupWidth * 0.2f
            val barWidth = (groupWidth - gap) / series
            groups.forEachIndexed { groupIndex, group ->
                group.values.forEachIndexed { index, value ->
                    val barHeight = (size.height * value / max).toFloat()
                    drawRoundRect(
                        color = colors.getOrElse(index) { colors.last() },
                        topLeft = Offset(groupIndex * groupWidth + gap / 2 + index * barWidth, size.height - barHeight),
                        size = Size((barWidth - 1f).coerceAtLeast(1f), barHeight),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
        AxisLabels(groups.map { it.label })
    }
}

/** Сектор кольцевой диаграммы. */
data class ChartSlice(val label: String, val value: Double, val color: Color, val valueText: String = "")

/**
 * Кольцевая диаграмма с подписью в центре.
 * @param centerText текст в центре кольца, например общая сумма
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    description: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 140.dp,
    centerText: String = "",
    centerCaption: String = "",
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(diameter)) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
            val stroke = size.minDimension * 0.16f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val total = slices.sumOf { it.value }
            if (total <= 0) {
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                return@Canvas
            }
            var start = -90f
            slices.forEach { slice ->
                val sweep = (slice.value / total * 360).toFloat()
                // Небольшой зазор между секторами, чтобы они различались не только цветом
                val gap = if (slices.size > 1 && sweep > 3f) 1.5f else 0f
                drawArc(slice.color, start + gap / 2, sweep - gap, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            if (centerText.isNotEmpty()) {
                Text(centerText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (centerCaption.isNotEmpty()) {
                Text(centerCaption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Легенда диаграммы: цветная метка, название и значение каждого ряда. */
@Composable
fun ChartLegend(slices: List<ChartSlice>, modifier: Modifier = Modifier) {
    Column(modifier) {
        slices.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(slice.color))
                Spacer(Modifier.width(8.dp))
                Text(
                    slice.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(slice.valueText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
            }
        }
    }
}

/** Цвета рядов для диаграмм без собственных цветов категорий. */
val ChartPalette = listOf(
    Color(0xFF1B5E9B), Color(0xFF00897B), Color(0xFFEF6C00), Color(0xFF6A1B9A),
    Color(0xFFAD1457), Color(0xFF558B2F), Color(0xFF546E7A),
)
