package ru.finhelper.app.ui.transactions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finhelper.app.R
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.local.TemplateEntity
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.ui.components.AppScaffold
import ru.finhelper.app.ui.components.AppTextField
import ru.finhelper.app.ui.components.ConfirmDialog
import ru.finhelper.app.ui.components.DateField
import ru.finhelper.app.ui.components.GroupTitle
import ru.finhelper.app.ui.components.LoadingBox
import ru.finhelper.app.ui.components.LocalAppContainer
import ru.finhelper.app.ui.components.MoneyField
import ru.finhelper.app.ui.components.NoticeBanner
import ru.finhelper.app.ui.components.PrimaryButton
import ru.finhelper.app.ui.components.SectionCard
import ru.finhelper.app.ui.components.SelectField
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.components.TooltipIconButton
import java.io.File

/** Создает во временном каталоге файл для снимка чека и возвращает его адрес для приложения камеры. */
private fun newReceiptUri(context: Context): Uri {
    val directory = File(context.cacheDir, "receipts").apply { mkdirs() }
    val file = File.createTempFile("receipt_", ".jpg", directory)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

@Composable
fun TransactionEditRoute(viewModel: TransactionEditViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val messages = LocalAppContainer.current.messages
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val defaultAccountName = stringResource(R.string.transaction_default_account)
    val voicePrompt = stringResource(R.string.transaction_voice_prompt)

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) photoUri?.let(viewModel::recognizeReceipt)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::recognizeReceipt)
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(viewModel::applyVoice)
    }
    TransactionEditScreen(
        state = state,
        onBack = onBack,
        onChange = viewModel::update,
        onKindChange = viewModel::setKind,
        onCategoryChange = viewModel::setCategory,
        onMerchantChange = viewModel::setMerchant,
        onTemplate = viewModel::applyTemplate,
        onTakePhoto = {
            try {
                photoUri = newReceiptUri(context).also(cameraLauncher::launch)
            } catch (_: ActivityNotFoundException) {
                messages.show(R.string.transaction_camera_unavailable)
            }
        },
        onPickPhoto = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onVoice = {
            // Голосовой ввод выполняется встроенными средствами распознавания речи Android (п. 4.3.4.1 д ТЗ)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
            try {
                voiceLauncher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                messages.show(R.string.transaction_voice_unavailable)
            }
        },
        onSave = { viewModel.save(defaultAccountName, onBack) },
        onDelete = { viewModel.delete(onBack) },
    )
}

/**
 * Экран «Добавить транзакцию»: сумма, счет, категория и дата объединены в один
 * блок, способы быстрого ввода – в другой; кнопка «Сохранить» расположена внизу
 * (п. 4.4.5.11, 4.4.5.13 ТЗ). При открытии фокус устанавливается на поле суммы.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditScreen(
    state: TransactionFormState,
    onBack: () -> Unit = {},
    onChange: ((TransactionFormState) -> TransactionFormState) -> Unit = {},
    onKindChange: (String) -> Unit = {},
    onCategoryChange: (String) -> Unit = {},
    onMerchantChange: (String) -> Unit = {},
    onTemplate: (TemplateEntity) -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onPickPhoto: () -> Unit = {},
    onVoice: () -> Unit = {},
    onSave: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(state.loading) { if (!state.loading && state.id == null) runCatching { amountFocus.requestFocus() } }
    AppScaffold(
        title = stringResource(if (state.id == null) R.string.transaction_add_title else R.string.transaction_edit_title),
        onBack = onBack,
        actions = {
            if (state.id != null) TooltipIconButton(Icons.Filled.Delete, stringResource(R.string.action_delete), { confirmDelete = true })
        },
        bottomBar = {
            PrimaryButton(
                stringResource(R.string.action_save), onSave, loading = state.saving, enabled = !state.recognizing,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@AppScaffold
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            val kinds = listOf(
                BalanceCalculator.EXPENSE to R.string.kind_expense,
                BalanceCalculator.INCOME to R.string.kind_income,
                BalanceCalculator.TRANSFER to R.string.kind_transfer,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, (kind, title) ->
                    SegmentedButton(
                        selected = state.kind == kind,
                        onClick = { onKindChange(kind) },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(title)) }
                }
            }
            if (state.recognizing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.transaction_recognizing), style = MaterialTheme.typography.bodySmall)
            }
            SectionCard(spacing = 8.dp) {
                MoneyField(
                    state.amount, { value -> onChange { it.copy(amount = value, amountError = null) } },
                    stringResource(R.string.field_amount), state.currency, error = state.amountError,
                    modifier = Modifier.focusRequester(amountFocus),
                )
                if (state.accounts.isEmpty()) {
                    NoticeBanner(stringResource(R.string.transaction_default_account_hint), tone = Tone.NEUTRAL, icon = Icons.Filled.AutoAwesome)
                } else {
                    SelectField(
                        stringResource(R.string.field_account), state.accounts.firstOrNull { it.id == state.accountId }, state.accounts,
                        Option::label, { option -> onChange { it.copy(accountId = option.id, accountError = null) } }, error = state.accountError,
                    )
                }
                if (state.kind == BalanceCalculator.TRANSFER) {
                    SelectField(
                        stringResource(R.string.transaction_to_account), state.accounts.firstOrNull { it.id == state.toAccountId },
                        state.accounts.filter { it.id != state.accountId }, Option::label,
                        { option -> onChange { it.copy(toAccountId = option.id, toAccountError = null) } }, error = state.toAccountError,
                    )
                    state.toCurrency?.let { currency ->
                        MoneyField(
                            state.amountTo, { value -> onChange { it.copy(amountTo = value) } },
                            stringResource(R.string.transaction_amount_to), currency,
                            hint = stringResource(R.string.transaction_amount_to_hint),
                        )
                    }
                } else {
                    SelectField(
                        stringResource(R.string.field_category), state.categories.firstOrNull { it.id == state.categoryId }, state.categories,
                        Option::label, { onCategoryChange(it.id) }, error = state.categoryError,
                    )
                    if (state.categorySuggested) {
                        Row {
                            Icon(
                                Icons.Filled.AutoAwesome, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp),
                            )
                            Text(
                                stringResource(R.string.transaction_auto_category),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
                DateField(stringResource(R.string.field_date), state.date, { value -> onChange { it.copy(date = value) } })
            }
            SectionCard(spacing = 8.dp) {
                AppTextField(state.merchant, onMerchantChange, stringResource(R.string.transaction_merchant))
                AppTextField(
                    state.comment, { value -> onChange { it.copy(comment = value.take(500)) } },
                    stringResource(R.string.field_comment), imeAction = ImeAction.Done,
                )
            }
            if (state.id == null) QuickInput(state.templates, onTemplate, onTakePhoto, onPickPhoto, onVoice)
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.transaction_delete_title),
            text = stringResource(R.string.transaction_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Блок «Быстрый ввод»: шаблон, фотография чека, голос (п. 4.2.1.1 б ТЗ). */
@Composable
private fun QuickInput(
    templates: List<TemplateEntity>,
    onTemplate: (TemplateEntity) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onVoice: () -> Unit,
) {
    var templatesOpen by remember { mutableStateOf(false) }
    var photoOpen by remember { mutableStateOf(false) }
    Column {
        GroupTitle(stringResource(R.string.transaction_quick_input))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                QuickButton(Icons.Filled.FileCopy, R.string.transaction_from_template) { templatesOpen = true }
                DropdownMenu(expanded = templatesOpen, onDismissRequest = { templatesOpen = false }) {
                    if (templates.isEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.transaction_no_templates)) }, onClick = { templatesOpen = false })
                    }
                    templates.forEach { template ->
                        DropdownMenuItem(
                            text = { Text("${template.name} · ${Money.format(template.amount, template.currency)}") },
                            onClick = { templatesOpen = false; onTemplate(template) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                QuickButton(Icons.Filled.PhotoCamera, R.string.transaction_from_receipt) { photoOpen = true }
                DropdownMenu(expanded = photoOpen, onDismissRequest = { photoOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.transaction_take_photo)) }, onClick = { photoOpen = false; onTakePhoto() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.transaction_pick_photo)) }, onClick = { photoOpen = false; onPickPhoto() })
                }
            }
            Box(Modifier.weight(1f)) { QuickButton(Icons.Filled.Mic, R.string.transaction_from_voice, onVoice) }
        }
    }
}

@Composable
private fun QuickButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(label), maxLines = 1, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
    }
}
