package ru.finhelper.app.ui.components

import ru.finhelper.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.finhelper.app.core.Money
import ru.finhelper.app.ui.theme.LocalFinColors

/**
 * Кнопка-значок с всплывающей подсказкой и описанием для программ экранного
 * доступа (п. 4.4.5.21 ТЗ). Размер области нажатия – не менее 48×48 dp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
            if (tint == Color.Unspecified) Icon(icon, contentDescription = description) else Icon(icon, contentDescription = description, tint = tint)
        }
    }
}

/**
 * Карточка-блок с заголовком: объединяет связанные сведения (п. 4.4.5.11 ТЗ).
 * @param spacing расстояние между элементами содержимого
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    action()
                }
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, elevation = elevation, content = body)
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = colors, elevation = elevation, content = body)
    }
}

/** Вид смыслового выделения: успех, ошибка, предупреждение или нейтральное. */
enum class Tone { POSITIVE, NEGATIVE, WARNING, NEUTRAL }

@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.POSITIVE -> LocalFinColors.current.income
    Tone.NEGATIVE -> LocalFinColors.current.expense
    Tone.WARNING -> LocalFinColors.current.warning
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun toneContainer(tone: Tone): Color = when (tone) {
    Tone.POSITIVE -> LocalFinColors.current.incomeContainer
    Tone.NEGATIVE -> LocalFinColors.current.expenseContainer
    Tone.WARNING -> LocalFinColors.current.warningContainer
    Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
}

/** Метка статуса: значок и текст на цветной подложке. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(color = toneContainer(tone), shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = toneColor(tone), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Индикатор выполнения с указанием процента (п. 4.4.5.31 ТЗ). Полоса заполняется
 * не более чем на 100 %, фактический процент выводится числом.
 */
@Composable
fun PercentBar(percent: Double, tone: Tone, modifier: Modifier = Modifier, description: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { (percent / 100.0).coerceIn(0.0, 1.0).toFloat() },
            color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.primary else toneColor(tone),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .semantics { if (description.isNotEmpty()) contentDescription = description },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            Money.formatPercent(percent),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}

/** Строка «подпись – значение»; значение выравнивается по правому краю (п. 4.4.5.17 ТЗ). */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified, bold: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else null,
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

/** Основная кнопка формы; располагается в нижней части экрана (п. 4.4.5.13 ТЗ). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        if (loading) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

/**
 * Диалог подтверждения необратимого действия (п. 4.4.5.26 ТЗ): кнопка действия
 * не является кнопкой по умолчанию, в диалоге две кнопки.
 */
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        dismissButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
    )
}

/** Диалог с формой: заголовок, поля и кнопки «Отмена» и подтверждения. */
@Composable
fun FormDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = { Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Заглушка пустого списка с пояснением, что сделать дальше. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Category) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(32.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Индикатор хода длительной операции (п. 4.4.5.36 ТЗ). */
@Composable
fun LoadingBox(modifier: Modifier = Modifier, text: String = stringResource(R.string.loading)) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxSize().padding(32.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Сообщение об ошибке загрузки с кнопкой повтора. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth().padding(32.dp)) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = LocalFinColors.current.expense, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/** Полоса-предупреждение, например о показе сохраненных данных без сети. */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.WARNING, icon: ImageVector = Icons.Filled.CloudOff) {
    Surface(color = toneContainer(tone), shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = toneColor(tone), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Круглый значок категории с ее цветом. */
@Composable
fun CategoryIcon(iconName: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
    ) {
        Icon(categoryIcon(iconName), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}

/** Значок по имени из справочника категорий сервера. */
fun categoryIcon(name: String): ImageVector = when (name) {
    "shopping_cart" -> Icons.Filled.ShoppingCart
    "restaurant" -> Icons.Filled.Restaurant
    "directions_bus" -> Icons.Filled.DirectionsBus
    "home" -> Icons.Filled.Home
    "phone_android" -> Icons.Filled.PhoneAndroid
    "medical_services" -> Icons.Filled.MedicalServices
    "checkroom" -> Icons.Filled.Checkroom
    "movie" -> Icons.Filled.Movie
    "school" -> Icons.Filled.School
    "flight" -> Icons.Filled.Flight
    "account_balance" -> Icons.Filled.AccountBalance
    "redeem" -> Icons.Filled.Redeem
    "chair" -> Icons.Filled.Chair
    "more_horiz" -> Icons.Filled.MoreHoriz
    "payments" -> Icons.Filled.Payments
    "work" -> Icons.Filled.Work
    "savings" -> Icons.Filled.Savings
    "card_giftcard" -> Icons.Filled.CardGiftcard
    "add_circle" -> Icons.Filled.AddCircle
    "transfer" -> Icons.Filled.SwapHoriz
    else -> Icons.Filled.Category
}

/** Заголовок группы внутри списка или формы. */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(vertical = 8.dp).semantics { heading() },
    )
}
