package ru.finhelper.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.finhelper.app.R
import ru.finhelper.app.data.sync.SyncStatus
import ru.finhelper.app.ui.theme.LocalFinColors

/** Состояние синхронизации для индикатора в шапке каждого экрана. */
val LocalSyncStatus = compositionLocalOf { SyncStatus() }

/** Действие по нажатию на индикатор синхронизации: запуск синхронизации вручную. */
val LocalSyncAction = staticCompositionLocalOf<() -> Unit> { {} }

/** Общая для всех экранов область всплывающих сообщений о результате действий (п. 4.4.5.35 ТЗ). */
val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/** Переход между разделами нижней панели навигации. */
val LocalTabNavigator = staticCompositionLocalOf<(MainTab) -> Unit> { {} }

/** Разделы нижней панели навигации – не более пяти (п. 4.4.5.8 ТЗ). */
enum class MainTab(val route: String, @StringRes val title: Int, val icon: ImageVector) {
    DASHBOARD("dashboard", R.string.tab_dashboard, Icons.Filled.Home),
    TRANSACTIONS("transactions", R.string.tab_transactions, Icons.AutoMirrored.Filled.ReceiptLong),
    BUDGET("budget", R.string.tab_budget, Icons.Filled.PieChart),
    GOALS("goals", R.string.tab_goals, Icons.Filled.Flag),
    INVESTMENTS("investments", R.string.tab_investments, Icons.AutoMirrored.Filled.ShowChart),
}

/**
 * Каркас экрана: заголовок, кнопка возврата (п. 4.4.5.34 ТЗ), постоянный индикатор
 * синхронизации (п. 4.4.5.33 ТЗ), область сообщений и нижняя панель навигации.
 *
 * @param onBack действие кнопки «Назад»; null – экран верхнего уровня
 * @param tab раздел нижней панели, к которому относится экран; null – панель не показывается
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    tab: MainTab? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val navigateToTab = LocalTabNavigator.current
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (onBack != null) {
                            TooltipIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
                        } else {
                            navigationIcon()
                        }
                    },
                    actions = actions,
                )
                SyncStatusBar(LocalSyncStatus.current, LocalSyncAction.current)
            }
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHost.current) },
        floatingActionButton = floatingActionButton,
        bottomBar = {
            // Панель навигации сама учитывает системные отступы; для кнопок формы они добавляются здесь
            Column(if (tab == null) Modifier.navigationBarsPadding() else Modifier) {
                bottomBar()
                if (tab != null) MainBottomBar(tab, navigateToTab)
            }
        },
        content = content,
    )
}

@Composable
private fun MainBottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { if (tab != selected) onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.title), maxLines = 1, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/** Текст индикатора синхронизации: режим «онлайн / офлайн» и состояние очереди операций. */
@Composable
fun syncStatusText(status: SyncStatus): String {
    val pending = if (status.pending > 0) stringResource(pendingOperationsText(status.pending), status.pending) else null
    return when {
        status.maintenance -> stringResource(R.string.sync_maintenance, pending ?: stringResource(R.string.sync_saved_on_device))
        !status.online -> stringResource(R.string.sync_offline, pending ?: stringResource(R.string.sync_saved_on_device))
        status.syncing -> stringResource(R.string.sync_in_progress)
        status.error != null && pending != null -> stringResource(R.string.sync_error_pending, pending)
        status.error != null -> stringResource(R.string.sync_error)
        pending != null -> stringResource(R.string.sync_online, pending)
        else -> stringResource(R.string.sync_done)
    }
}

/**
 * Строка «ожидает N операций» в нужной форме числа. Форма выбирается по правилам
 * русского языка независимо от языка, установленного на устройстве.
 */
@StringRes
fun pendingOperationsText(count: Int): Int = when {
    count % 100 in 11..14 -> R.string.sync_pending_many
    count % 10 == 1 -> R.string.sync_pending_one
    count % 10 in 2..4 -> R.string.sync_pending_few
    else -> R.string.sync_pending_many
}

/**
 * Индикатор статуса синхронизации. Состояние передается значком и текстом, а не
 * только цветом; изменения объявляются программам экранного доступа.
 */
@Composable
fun SyncStatusBar(status: SyncStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalFinColors.current
    val synced = status.online && !status.maintenance && status.pending == 0 && status.error == null
    val (icon, tint, background) = when {
        status.maintenance -> Triple(Icons.Filled.Build, colors.warning, colors.warningContainer)
        !status.online -> Triple(Icons.Filled.CloudOff, colors.warning, colors.warningContainer)
        status.error != null -> Triple(Icons.Filled.CloudOff, colors.expense, colors.expenseContainer)
        synced && !status.syncing -> Triple(Icons.Filled.CloudDone, colors.income, colors.incomeContainer)
        else -> Triple(Icons.Filled.CloudSync, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
    }
    Surface(color = background, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.sync_now), onClick = onClick)
                .heightIn(min = 28.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                syncStatusText(status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
