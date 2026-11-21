package ru.finhelper.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.remote.AdminUserDto
import ru.finhelper.app.data.remote.AuditLogDto
import ru.finhelper.app.data.remote.MonitoringDto
import ru.finhelper.app.ui.auth.AuthValidation
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.AppTextField
import ru.finhelper.app.ui.components.ConfirmDialog
import ru.finhelper.app.ui.components.DateField
import ru.finhelper.app.ui.components.EmptyState
import ru.finhelper.app.ui.components.ErrorState
import ru.finhelper.app.ui.components.FormDialog
import ru.finhelper.app.ui.components.LineChart
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.SelectField
import ru.finhelper.app.ui.components.StatusChip
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import ru.finhelper.app.ui.components.toneColor
import ru.finhelper.app.ui.transactions.Option

@Composable
private fun roleOptions(): List<Option> = listOf(
    Option("user", stringResource(R.string.role_user)),
    Option("analyst", stringResource(R.string.role_analyst)),
    Option("admin", stringResource(R.string.role_admin)),
)

/**
 * Экран «Пользователи»: список и поиск учетных записей, создание учетной записи
 * с назначением роли, блокировка и разблокировка, сброс пароля (п. 4.2.1.8 е ТЗ).
 */
@Composable
fun UsersScreen(
    state: UsersUiState,
    onBack: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onCreate: (email: String, name: String, role: String, password: String) -> Unit = { _, _, _, _ -> },
    onSetBlocked: (AdminUserDto, Boolean) -> Unit = { _, _ -> },
    onResetPassword: (AdminUserDto) -> Unit = {},
    onDismissPassword: () -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf<AdminUserDto?>(null) }
    var resetting by remember { mutableStateOf<AdminUserDto?>(null) }
    val roles = roleOptions()
    LaunchedEffect(Unit) { onSearch(state.query) }
    AppScaffold(
        title = stringResource(R.string.admin_users_title),
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.users_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onSearch,
                label = { Text(stringResource(R.string.users_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            when {
                state.error != null && state.users.isEmpty() -> ErrorState(state.error, { onSearch(state.query) })
                state.loading && state.users.isEmpty() -> LoadingBox()
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(16.dp)) {
                    items(state.users, key = { it.id }) { user ->
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(user.displayName.ifBlank { user.email }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(user.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatusChip(roles.firstOrNull { it.id == user.role }?.label ?: user.role, Tone.NEUTRAL)
                            }
                            Spacer(Modifier.size(4.dp))
                            Text(
                                stringResource(
                                    R.string.users_last_login,
                                    user.lastLoginAt?.let(Dates::formatDateTime) ?: stringResource(R.string.users_never),
                                ),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (user.isBlocked) {
                                Spacer(Modifier.size(4.dp))
                                StatusChip(stringResource(R.string.users_status_blocked), Tone.NEGATIVE, icon = Icons.Filled.Block)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                OutlinedButton(
                                    onClick = { if (user.isBlocked) onSetBlocked(user, false) else blocking = user },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text(stringResource(if (user.isBlocked) R.string.users_unblock else R.string.users_block)) }
                                OutlinedButton(onClick = { resetting = user }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.users_reset_password))
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.size(88.dp)) }
                }
            }
        }
    }
    if (adding) UserDialog(roles, onCreate) { adding = false }
    blocking?.let { user ->
        ConfirmDialog(
            title = stringResource(R.string.users_block_title),
            text = stringResource(R.string.users_block_text, user.email),
            confirmLabel = stringResource(R.string.users_block),
            onConfirm = { onSetBlocked(user, true); blocking = null },
            onDismiss = { blocking = null },
        )
    }
    resetting?.let { user ->
        ConfirmDialog(
            title = stringResource(R.string.users_reset_title),
            text = stringResource(R.string.users_reset_text, user.email),
            confirmLabel = stringResource(R.string.users_reset_password),
            onConfirm = { onResetPassword(user); resetting = null },
            onDismiss = { resetting = null },
        )
    }
    state.temporaryPassword?.let { (email, password) ->
        AlertDialog(
            onDismissRequest = onDismissPassword,
            title = { Text(stringResource(R.string.users_temporary_password)) },
            text = { Text(stringResource(R.string.users_temporary_password_text, email, password)) },
            confirmButton = { TextButton(onClick = onDismissPassword) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun UserDialog(roles: List<Option>, onCreate: (String, String, String, String) -> Unit, onDismiss: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("user") }
    var password by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = stringResource(R.string.users_add),
        confirmLabel = stringResource(R.string.action_add),
        onConfirm = {
            emailError = AuthValidation.emailError(email)
            passwordError = if (password.isNotEmpty()) AuthValidation.passwordError(password) else null
            if (emailError == null && passwordError == null) {
                onCreate(email, name, role, password)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    ) {
        AppTextField(name, { name = it }, stringResource(R.string.register_name))
        AppTextField(email, { email = it; emailError = null }, stringResource(R.string.field_email), error = emailError, keyboardType = KeyboardType.Email)
        AppTextField(
            password, { password = it; passwordError = null }, stringResource(R.string.field_password), error = passwordError,
            hint = stringResource(R.string.users_password_hint), password = true,
        )
        SelectField(stringResource(R.string.users_role), roles.firstOrNull { it.id == role }, roles, Option::label, { role = it.id })
    }
}

/**
 * Экран «Логи»: журнал аудита с фильтрами по пользователю, периоду и типу
 * действия, экспортом в файл CSV и импортом внешнего журнала (п. 4.2.1.8 ж ТЗ).
 */
@Composable
fun LogsScreen(
    state: LogsUiState,
    onBack: () -> Unit = {},
    onLoad: () -> Unit = {},
    onFilterChange: ((LogFilter) -> LogFilter) -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onLoad() }
    AppScaffold(
        title = stringResource(R.string.admin_logs_title),
        onBack = onBack,
        actions = {
            Box {
                TooltipIconButton(Icons.Filled.MoreVert, stringResource(R.string.action_more), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.logs_export)) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.logs_import)) }, onClick = { menu = false; onImport() })
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AppTextField(state.filter.user, { value -> onFilterChange { it.copy(user = value) } }, stringResource(R.string.logs_user))
                    AppTextField(
                        state.filter.action, { value -> onFilterChange { it.copy(action = value) } }, stringResource(R.string.logs_action),
                        hint = stringResource(R.string.logs_action_hint),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateField(stringResource(R.string.date_from), state.filter.from, { value -> onFilterChange { it.copy(from = value) } }, Modifier.weight(1f))
                        DateField(stringResource(R.string.date_to), state.filter.to, { value -> onFilterChange { it.copy(to = value) } }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.size(4.dp))
                    Button(onClick = onLoad, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.action_apply)) }
                    Text(
                        stringResource(R.string.logs_total, state.total),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            when {
                state.error != null -> item { ErrorState(state.error, onLoad) }
                state.loading -> item { LoadingBox(Modifier.heightIn(min = 160.dp)) }
                state.items.isEmpty() -> item { EmptyState(stringResource(R.string.logs_empty)) }
                else -> items(state.items, key = { it.id }) { row -> LogRow(row) }
            }
        }
    }
}

@Composable
private fun LogRow(row: AuditLogDto) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(row.action, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Dates.formatDateTime(row.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row {
            Text(
                row.userEmail.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.ip.ifBlank { if (row.origin == "import") stringResource(R.string.logs_imported_mark) else "" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
            )
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

/**
 * Экран «Мониторинг»: доступность серверной части, среднее время ответа,
 * количество ошибок и график доступности за выбранный период (п. 4.2.1.8 з ТЗ).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringScreen(state: MonitoringUiState, onBack: () -> Unit = {}, onLoad: (Int) -> Unit = {}) {
    LaunchedEffect(Unit) { onLoad(state.hours) }
    AppScaffold(title = stringResource(R.string.admin_monitoring_title), onBack = onBack) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            val periods = listOf(24 to R.string.monitoring_day, 168 to R.string.monitoring_week, 720 to R.string.monitoring_month)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                periods.forEachIndexed { index, (hours, title) ->
                    SegmentedButton(
                        selected = state.hours == hours, onClick = { onLoad(hours) },
                        shape = SegmentedButtonDefaults.itemShape(index, periods.size), modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(title)) }
                }
            }
            val summary = state.summary
            when {
                summary != null -> MonitoringContent(summary, state)
                state.loading -> LoadingBox(Modifier.heightIn(min = 240.dp))
                else -> ErrorState(state.error ?: stringResource(R.string.online_only), { onLoad(state.hours) })
            }
        }
    }
}

@Composable
private fun MonitoringContent(summary: MonitoringDto, state: MonitoringUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(stringResource(R.string.monitoring_availability), Money.formatPercent(summary.availabilityPercent), Modifier.weight(1f))
        MetricCard(
            stringResource(R.string.monitoring_response),
            stringResource(R.string.monitoring_ms, Money.formatNumber(summary.averageResponseMs.toBigDecimal(), 0)), Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(stringResource(R.string.monitoring_errors), (summary.serverErrors + summary.loggedErrors).toString(), Modifier.weight(1f))
        MetricCard(stringResource(R.string.monitoring_requests), Money.formatNumber(summary.requests.toBigDecimal(), 0), Modifier.weight(1f))
    }
    SectionCard(title = stringResource(R.string.monitoring_components)) {
        val titles = mapOf(
            "database" to R.string.monitoring_database, "ml_service" to R.string.monitoring_ml, "rates" to R.string.monitoring_rates,
        )
        summary.components.forEach { (key, ok) ->
            ComponentRow(titles[key]?.let { stringResource(it) } ?: key, ok)
        }
        summary.lastCheckAt?.let {
            Text(
                stringResource(R.string.monitoring_last_check, Dates.formatDateTime(it)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SectionCard(title = stringResource(R.string.monitoring_chart)) {
        if (summary.availabilityChart.isEmpty()) {
            Text(stringResource(R.string.asset_no_data), style = MaterialTheme.typography.bodyMedium)
        } else {
            LineChart(
                values = summary.availabilityChart.map { it.percent },
                labels = summary.availabilityChart.map { hourLabel(it.hour) },
                description = stringResource(R.string.monitoring_chart_description),
                color = toneColor(Tone.POSITIVE),
                maxLabel = "100 %",
                minLabel = "0 %",
            )
        }
    }
    SectionCard(title = stringResource(R.string.monitoring_last_errors)) {
        if (state.errors.isEmpty()) Text(stringResource(R.string.monitoring_no_errors), style = MaterialTheme.typography.bodyMedium)
        state.errors.take(MAX_ERRORS).forEach { error ->
            Text(
                "${Dates.formatDateTime(error.at)} · ${error.source} · ${error.kind}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(error.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(6.dp))
        }
    }
}

private const val MAX_ERRORS = 10

/** Подпись точки графика: «2026-11-14 09:00» -> «14.11 09:00». */
private fun hourLabel(hour: String): String =
    if (hour.length >= 16) "${hour.substring(8, 10)}.${hour.substring(5, 7)} ${hour.substring(11, 16)}" else hour

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun ComponentRow(title: String, ok: Boolean) {
    val icon: ImageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(icon, contentDescription = null, tint = toneColor(if (ok) Tone.POSITIVE else Tone.NEGATIVE), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (ok) R.string.monitoring_ok else R.string.monitoring_failed),
            style = MaterialTheme.typography.bodyMedium, color = toneColor(if (ok) Tone.POSITIVE else Tone.NEGATIVE),
        )
    }
}

/**
 * Экран «Настройки системы»: параметры интеграций, порог уведомлений по
 * умолчанию, расписание резервного копирования и режим технического
 * обслуживания (п. 4.2.1.8 и ТЗ). Каждый раздел сохраняется отдельной кнопкой.
 */
@Composable
fun SystemSettingsScreen(
    state: SystemSettingsUiState,
    onBack: () -> Unit = {},
    onLoad: () -> Unit = {},
    onSave: (String, JsonObject) -> Unit = { _, _ -> },
) {
    LaunchedEffect(Unit) { onLoad() }
    AppScaffold(title = stringResource(R.string.admin_settings_title), onBack = onBack) { padding ->
        when {
            state.settings.isNotEmpty() -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                state.settings.forEach { (key, value) -> SettingSection(key, value, onSave) }
            }
            state.loading -> LoadingBox(Modifier.padding(padding))
            else -> ErrorState(state.error ?: stringResource(R.string.online_only), onLoad, Modifier.padding(padding))
        }
    }
}

private val SECTION_TITLES = mapOf(
    "integrations" to R.string.system_integrations, "notifications" to R.string.system_notifications,
    "backup" to R.string.system_backup, "maintenance" to R.string.system_maintenance,
)
private val FIELD_TITLES = mapOf(
    "rates_provider" to R.string.system_rates_provider, "quotes_provider" to R.string.system_quotes_provider,
    "push_provider" to R.string.system_push_provider, "email_provider" to R.string.system_email_provider,
    "budget_warning_percent" to R.string.system_budget_percent, "cron" to R.string.system_backup_cron,
    "keep_days" to R.string.system_backup_keep, "enabled" to R.string.system_maintenance_enabled,
    "message" to R.string.system_maintenance_message,
)

@Composable
private fun SettingSection(key: String, value: JsonObject, onSave: (String, JsonObject) -> Unit) {
    // Правки раздела хранятся до нажатия «Сохранить»; при обновлении данных с сервера сбрасываются
    val edits = remember(value) { mutableStateMapOf<String, JsonPrimitive>() }
    SectionCard(title = SECTION_TITLES[key]?.let { stringResource(it) } ?: key) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            value.forEach { (field, original) ->
                val primitive = edits[field] ?: (original as? JsonPrimitive) ?: return@forEach
                val title = FIELD_TITLES[field]?.let { stringResource(it) } ?: field
                val flag = primitive.booleanOrNull
                if (flag != null && !primitive.isString) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(flag, role = Role.Switch) { edits[field] = JsonPrimitive(it) },
                    ) {
                        Text(title, modifier = Modifier.weight(1f))
                        Switch(checked = flag, onCheckedChange = null)
                    }
                } else {
                    val numeric = !primitive.isString && primitive.longOrNull != null
                    AppTextField(
                        primitive.content,
                        { text -> edits[field] = if (numeric) JsonPrimitive(text.filter(Char::isDigit).toLongOrNull() ?: 0) else JsonPrimitive(text) },
                        title,
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                        alignEnd = numeric,
                    )
                }
            }
            Button(
                onClick = { onSave(key, JsonObject(value + edits)) },
                enabled = edits.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}
