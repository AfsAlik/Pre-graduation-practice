package ru.finhelper.app.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.local.NotificationEntity
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.remote.FaqItem
import ru.finhelper.app.data.remote.NotificationSettingsDto
import ru.finhelper.app.data.remote.ProfileDto
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.AppTextField
import ru.finhelper.app.ui.components.ConfirmDialog
import ru.finhelper.app.ui.components.EmptyState
import ru.finhelper.app.ui.components.FormDialog
import ru.finhelper.app.ui.components.GroupTitle
import ru.finhelper.app.ui.components.NoticeBanner
import ru.finhelper.app.ui.components.PrimaryButton
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.SelectField
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.navigation.Routes
import ru.finhelper.app.ui.transactions.Option
import kotlin.math.roundToInt

/** Переключатель с подписью: вся строка является областью нажатия (не менее 48 dp). */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Группа переключателей с названием; варианты расположены вертикально (п. 4.4.5.29 ТЗ). */
@Composable
private fun RadioGroup(title: String, options: List<Option>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.selectableGroup()) {
        GroupTitle(title)
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = option.id == selected, role = Role.RadioButton) { onSelect(option.id) },
            ) {
                RadioButton(selected = option.id == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(option.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun roleTitle(role: String): String = stringResource(
    when (role) {
        "admin" -> R.string.role_admin
        "analyst" -> R.string.role_analyst
        else -> R.string.role_user
    }
)

/**
 * Экран «Профиль»: базовая валюта, риск-профиль, инвестиционный горизонт, метод
 * расчета себестоимости, цветовая тема, переходы к настройкам уведомлений,
 * безопасности и справке, выход из учетной записи.
 */
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onBack: () -> Unit = {},
    onChange: ((ProfileDto) -> ProfileDto) -> Unit = {},
    onNavigate: (String) -> Unit = {},
    onLogout: () -> Unit = {},
) {
    var confirmLogout by remember { mutableStateOf(false) }
    val profile = state.profile
    AppScaffold(title = stringResource(R.string.profile_title), onBack = onBack) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AccountCircle, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(profile.displayName.ifBlank { state.email }, style = MaterialTheme.typography.titleMedium)
                        Text(state.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.profile_role, roleTitle(profile.role)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            SectionCard(title = stringResource(R.string.profile_finance)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectField(
                        stringResource(R.string.profile_base_currency), profile.baseCurrency, state.currencies,
                        { "$it (${Money.symbol(it)})" }, { value -> onChange { it.copy(baseCurrency = value) } },
                    )
                    val risks = listOf(
                        Option("conservative", stringResource(R.string.risk_conservative)),
                        Option("moderate", stringResource(R.string.risk_moderate)),
                        Option("aggressive", stringResource(R.string.risk_aggressive)),
                    )
                    SelectField(
                        stringResource(R.string.profile_risk), risks.firstOrNull { it.id == profile.riskProfile }, risks, Option::label,
                        { option -> onChange { it.copy(riskProfile = option.id) } },
                    )
                    SelectField(
                        stringResource(R.string.profile_horizon), profile.investHorizonYears,
                        (HORIZONS + profile.investHorizonYears).distinct().sorted(),
                        { years -> years.toString() }, { value -> onChange { it.copy(investHorizonYears = value) } },
                    )
                    val methods = listOf(
                        Option("average", stringResource(R.string.costing_average)), Option("fifo", stringResource(R.string.costing_fifo)),
                    )
                    SelectField(
                        stringResource(R.string.profile_costing), methods.firstOrNull { it.id == profile.costingMethod }, methods, Option::label,
                        { option -> onChange { it.copy(costingMethod = option.id) } },
                    )
                }
            }
            SectionCard {
                RadioGroup(
                    title = stringResource(R.string.profile_theme),
                    options = listOf(
                        Option(SettingsStore.THEME_LIGHT, stringResource(R.string.theme_light)),
                        Option(SettingsStore.THEME_DARK, stringResource(R.string.theme_dark)),
                        Option(SettingsStore.THEME_SYSTEM, stringResource(R.string.theme_system)),
                    ),
                    selected = profile.theme,
                    onSelect = { value -> onChange { it.copy(theme = value) } },
                )
            }
            SectionCard {
                LinkRow(Icons.Filled.Notifications, stringResource(R.string.notification_settings_title)) { onNavigate(Routes.NOTIFICATION_SETTINGS) }
                LinkRow(Icons.Filled.Security, stringResource(R.string.security_title)) { onNavigate(Routes.SECURITY) }
                LinkRow(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.help_title)) { onNavigate(Routes.HELP) }
            }
            OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.profile_logout))
            }
        }
    }
    if (confirmLogout) {
        ConfirmDialog(
            title = stringResource(R.string.profile_logout_title),
            text = if (state.pendingOperations > 0) {
                stringResource(R.string.profile_logout_pending, state.pendingOperations)
            } else {
                stringResource(R.string.profile_logout_text)
            },
            confirmLabel = stringResource(R.string.profile_logout),
            onConfirm = { confirmLogout = false; onLogout() },
            onDismiss = { confirmLogout = false },
        )
    }
}

private val HORIZONS = listOf(1, 3, 5, 10, 15, 20, 30)

/**
 * Экран настройки уведомлений: каналы, порог срабатывания предупреждения о
 * бюджете, частота и виды уведомлений (п. 4.2.1.6 в ТЗ).
 */
@Composable
fun NotificationSettingsScreen(
    settings: NotificationSettingsDto,
    onBack: () -> Unit = {},
    onChange: ((NotificationSettingsDto) -> NotificationSettingsDto) -> Unit = {},
) {
    var threshold by remember(settings.budgetThresholdPercent) { mutableFloatStateOf(settings.budgetThresholdPercent.toFloat()) }
    AppScaffold(title = stringResource(R.string.notification_settings_title), onBack = onBack) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SectionCard(title = stringResource(R.string.notification_channels)) {
                SwitchRow(stringResource(R.string.notification_push), settings.pushEnabled, { value -> onChange { it.copy(pushEnabled = value) } })
                SwitchRow(
                    stringResource(R.string.notification_email), settings.emailEnabled, { value -> onChange { it.copy(emailEnabled = value) } },
                    stringResource(R.string.notification_email_hint),
                )
            }
            SectionCard(title = stringResource(R.string.notification_threshold)) {
                Text(stringResource(R.string.notification_threshold_value, threshold.roundToInt()), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = { onChange { it.copy(budgetThresholdPercent = threshold.roundToInt()) } },
                    valueRange = 50f..100f,
                    steps = 9,
                )
            }
            SectionCard {
                RadioGroup(
                    title = stringResource(R.string.notification_frequency),
                    options = listOf(
                        Option("instant", stringResource(R.string.notification_instant)), Option("daily", stringResource(R.string.notification_daily)),
                    ),
                    selected = settings.frequency,
                    onSelect = { value -> onChange { it.copy(frequency = value) } },
                )
            }
            SectionCard(title = stringResource(R.string.notification_kinds)) {
                SwitchRow(stringResource(R.string.notification_kind_budget), settings.budget, { value -> onChange { it.copy(budget = value) } })
                SwitchRow(stringResource(R.string.notification_kind_recurring), settings.recurring, { value -> onChange { it.copy(recurring = value) } })
                SwitchRow(stringResource(R.string.notification_kind_goals), settings.goals, { value -> onChange { it.copy(goals = value) } })
                SwitchRow(
                    stringResource(R.string.notification_kind_recommendations), settings.recommendations,
                    { value -> onChange { it.copy(recommendations = value) } },
                )
                SwitchRow(stringResource(R.string.notification_kind_risks), settings.risks, { value -> onChange { it.copy(risks = value) } })
            }
        }
    }
}

/** Экран «Безопасность»: двухфакторная аутентификация, смена пароля и удаление учетной записи. */
@Composable
fun SecurityScreen(
    state: SecurityUiState,
    onBack: () -> Unit = {},
    onStartTwoFactor: () -> Unit = {},
    onEnableTwoFactor: (String) -> Unit = {},
    onDisableTwoFactor: (String) -> Unit = {},
    onChangePassword: (current: String, new: String) -> Unit = { _, _ -> },
    onDeleteAccount: (password: String) -> Unit = {},
) {
    var code by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var deleteDialog by remember { mutableStateOf(false) }
    AppScaffold(title = stringResource(R.string.security_title), onBack = onBack) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            state.error?.let { NoticeBanner(it, tone = Tone.NEGATIVE, icon = Icons.Filled.ErrorOutline) }
            SectionCard(title = stringResource(R.string.security_2fa)) {
                Text(
                    stringResource(if (state.totpEnabled) R.string.security_2fa_on else R.string.security_2fa_off),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(8.dp))
                when {
                    state.totpEnabled -> {
                        AppTextField(code, { code = it.filter(Char::isDigit).take(8) }, stringResource(R.string.security_code), keyboardType = KeyboardType.Number)
                        OutlinedButton(onClick = { onDisableTwoFactor(code); code = "" }, enabled = code.length >= 6 && !state.busy) {
                            Text(stringResource(R.string.security_2fa_disable))
                        }
                    }
                    state.totpSecret != null -> {
                        Text(stringResource(R.string.security_2fa_secret, state.totpSecret), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(8.dp))
                        AppTextField(code, { code = it.filter(Char::isDigit).take(8) }, stringResource(R.string.security_code), keyboardType = KeyboardType.Number)
                        Button(onClick = { onEnableTwoFactor(code); code = "" }, enabled = code.length >= 6 && !state.busy) {
                            Text(stringResource(R.string.security_2fa_confirm))
                        }
                    }
                    else -> Button(onClick = onStartTwoFactor, enabled = !state.busy) { Text(stringResource(R.string.security_2fa_enable)) }
                }
            }
            SectionCard(title = stringResource(R.string.security_password)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(currentPassword, { currentPassword = it }, stringResource(R.string.security_current_password), password = true)
                    AppTextField(
                        newPassword, { newPassword = it }, stringResource(R.string.recovery_new_password),
                        hint = stringResource(R.string.register_password_hint), password = true, imeAction = ImeAction.Done,
                    )
                    Button(
                        onClick = { onChangePassword(currentPassword, newPassword); currentPassword = ""; newPassword = "" },
                        enabled = currentPassword.isNotEmpty() && newPassword.isNotEmpty() && !state.busy,
                    ) { Text(stringResource(R.string.recovery_submit)) }
                }
            }
            SectionCard(title = stringResource(R.string.security_delete)) {
                Text(stringResource(R.string.security_delete_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { deleteDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.security_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (deleteDialog) {
        var password by remember { mutableStateOf("") }
        FormDialog(
            title = stringResource(R.string.security_delete_title),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { deleteDialog = false; onDeleteAccount(password) },
            onDismiss = { deleteDialog = false },
            confirmEnabled = password.isNotEmpty(),
        ) {
            Text(stringResource(R.string.security_delete_confirm), style = MaterialTheme.typography.bodyMedium)
            AppTextField(password, { password = it }, stringResource(R.string.field_password), password = true, imeAction = ImeAction.Done)
        }
    }
}

/** Экран «Справка»: ответы на частые вопросы и форма обращения в службу поддержки (п. 4.2.1.8 к ТЗ). */
@Composable
fun HelpScreen(
    state: HelpUiState,
    onBack: () -> Unit = {},
    onLoad: () -> Unit = {},
    onSend: (subject: String, message: String, onDone: () -> Unit) -> Unit = { _, _, _ -> },
) {
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var subjectError by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onLoad() }
    AppScaffold(title = stringResource(R.string.help_title), onBack = onBack) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        ) {
            item { GroupTitle(stringResource(R.string.help_faq)) }
            if (state.faq.isEmpty()) item { Text(state.error ?: stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium) }
            items(state.faq) { item -> FaqCard(item) }
            item { GroupTitle(stringResource(R.string.help_support)) }
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.faq.isNotEmpty()) state.error?.let { NoticeBanner(it, tone = Tone.NEGATIVE, icon = Icons.Filled.ErrorOutline) }
                        AppTextField(subject, { subject = it.take(200); subjectError = null }, stringResource(R.string.help_subject), error = subjectError)
                        AppTextField(
                            message, { message = it.take(5000); messageError = null }, stringResource(R.string.help_message),
                            error = messageError, singleLine = false, imeAction = ImeAction.Default,
                        )
                        PrimaryButton(
                            stringResource(R.string.action_send),
                            onClick = {
                                subjectError = if (subject.trim().length < 3) "Укажите тему обращения (не менее 3 символов)" else null
                                messageError = if (message.trim().length < 5) "Опишите вопрос (не менее 5 символов)" else null
                                if (subjectError == null && messageError == null) onSend(subject, message) { subject = ""; message = "" }
                            },
                            loading = state.sending,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FaqCard(item: FaqItem) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            Spacer(Modifier.size(8.dp))
            Text(item.answer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Экран «Уведомления»: уведомления сервера и уведомления, сформированные на устройстве. */
@Composable
fun NotificationsScreen(items: List<NotificationEntity>, onBack: () -> Unit = {}, onOpened: () -> Unit = {}) {
    LaunchedEffect(Unit) { onOpened() }
    AppScaffold(title = stringResource(R.string.notifications_title), onBack = onBack) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (items.isEmpty()) item { EmptyState(stringResource(R.string.notifications_empty), icon = Icons.Filled.Notifications) }
            items(items, key = { it.id }) { item ->
                SectionCard {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    if (item.body.isNotBlank()) Text(item.body, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        Dates.formatDateTime(item.createdAt),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
