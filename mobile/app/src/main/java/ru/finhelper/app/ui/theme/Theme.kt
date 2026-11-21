package ru.finhelper.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ru.finhelper.app.data.prefs.SettingsStore

/**
 * Смысловые цвета финансовой информации (п. 4.4.5.4 ТЗ): зеленый – доходы и
 * успешные операции, красный – расходы и ошибки, оранжевый – предупреждения.
 * Статус никогда не передается только цветом: рядом всегда есть текст или значок.
 */
@Immutable
data class FinColors(
    val income: Color,
    val expense: Color,
    val warning: Color,
    val incomeContainer: Color,
    val expenseContainer: Color,
    val warningContainer: Color,
)

private val LightFinColors = FinColors(
    income = Color(0xFF2E7D32),
    expense = Color(0xFFC62828),
    warning = Color(0xFFB85C00),
    incomeContainer = Color(0xFFE3F2E4),
    expenseContainer = Color(0xFFFBE4E4),
    warningContainer = Color(0xFFFFEBD6),
)

private val DarkFinColors = FinColors(
    income = Color(0xFF81C784),
    expense = Color(0xFFEF9A9A),
    warning = Color(0xFFFFB74D),
    incomeContainer = Color(0xFF1E3A21),
    expenseContainer = Color(0xFF4A1F1F),
    warningContainer = Color(0xFF4A3212),
)

val LocalFinColors = staticCompositionLocalOf { LightFinColors }

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E9B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E6F8),
    onPrimaryContainer = Color(0xFF0A2E52),
    secondary = Color(0xFF4F6078),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE5F2),
    onSecondaryContainer = Color(0xFF1A2A3D),
    tertiary = Color(0xFF00695C),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE7ECF3),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainer = Color(0xFFF0F3F8),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9EDF3),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFCDD3DC),
    error = Color(0xFFC62828),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCBFB),
    onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF12466F),
    onPrimaryContainer = Color(0xFFD5E6F8),
    secondary = Color(0xFFB7C8E1),
    onSecondary = Color(0xFF213246),
    secondaryContainer = Color(0xFF38485E),
    onSecondaryContainer = Color(0xFFDCE5F2),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1D21),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF2B3038),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceContainer = Color(0xFF1F2328),
    surfaceContainerLow = Color(0xFF1A1D21),
    surfaceContainerHigh = Color(0xFF292D33),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF3F444C),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF5F1414),
)

/**
 * Тема приложения в светлом и темном вариантах (п. 4.4.5.1 ТЗ).
 * @param mode режим темы: light, dark или system (как в настройках устройства)
 */
@Composable
fun FinHelperTheme(mode: String = SettingsStore.THEME_SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        SettingsStore.THEME_DARK -> true
        SettingsStore.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalFinColors provides if (dark) DarkFinColors else LightFinColors) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Typography(), content = content)
    }
}

/** Преобразует цвет вида «#RRGGBB» из справочника категорий. */
fun parseColor(hex: String, fallback: Color = Color(0xFF9E9E9E)): Color {
    val digits = hex.removePrefix("#")
    val value = digits.toLongOrNull(16) ?: return fallback
    return when (digits.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> fallback
    }
}
