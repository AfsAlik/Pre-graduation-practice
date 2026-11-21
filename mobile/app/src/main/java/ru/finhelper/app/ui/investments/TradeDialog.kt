package ru.finhelper.app.ui.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import ru.finhelper.app.R
import ru.finhelper.app.core.Dates
import ru.finhelper.app.core.Money
import ru.finhelper.app.data.local.TradeEntity
import ru.finhelper.app.ui.components.AppTextField
import ru.finhelper.app.ui.components.DateField
import ru.finhelper.app.ui.components.FormDialog
import ru.finhelper.app.ui.components.MoneyField
import ru.finhelper.app.ui.components.NoticeBanner
import ru.finhelper.app.ui.components.SelectField
import ru.finhelper.app.ui.components.Tone
import ru.finhelper.app.ui.transactions.Option

@Composable
fun assetTypeOptions(): List<Option> = listOf(
    Option("stock", stringResource(R.string.asset_type_stock)),
    Option("bond", stringResource(R.string.asset_type_bond)),
    Option("etf", stringResource(R.string.asset_type_etf)),
    Option("currency", stringResource(R.string.asset_type_currency)),
    Option("crypto", stringResource(R.string.asset_type_crypto)),
)

/** Разбирает количество или цену: до восьми знаков после запятой. */
private fun parseQuantity(text: String): BigDecimal? =
    text.replace(',', '.').filterNot(Char::isWhitespace).toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 8 }

private fun quantityInput(raw: String): String = raw.filter { it.isDigit() || it == ',' || it == '.' }.replace('.', ',').take(20)

/**
 * Окно «Добавить сделку»: тип и название актива, количество, цена и дата
 * (п. 4.2.1.3 а ТЗ). Для сделки старше 7 дней поля количества и цены недоступны
 * и выводится пояснение (п. 4.2.1.3 е ТЗ).
 *
 * @param trade изменяемая сделка; null – добавление
 * @param locked истек срок изменения количества и цены
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeDialog(
    trade: TradeEntity?,
    locked: Boolean,
    currencies: List<String>,
    defaultCurrency: String,
    now: Long,
    onSave: (TradeInput) -> Unit,
    onDismiss: () -> Unit,
    presetTicker: String = "",
    presetName: String = "",
    presetType: String = "stock",
) {
    val types = assetTypeOptions()
    var assetType by remember { mutableStateOf(trade?.assetType ?: presetType) }
    var ticker by remember { mutableStateOf(trade?.ticker ?: presetTicker) }
    var name by remember { mutableStateOf(trade?.name ?: presetName) }
    var side by remember { mutableStateOf(trade?.side ?: "buy") }
    var quantity by remember { mutableStateOf(trade?.quantity?.let(Money::formatQuantity)?.filterNot { it == Money.GROUP_SEPARATOR }.orEmpty()) }
    var price by remember { mutableStateOf(trade?.price?.let(Money::formatQuantity)?.filterNot { it == Money.GROUP_SEPARATOR }.orEmpty()) }
    var fee by remember { mutableStateOf(Money.toInput(trade?.fee)) }
    var currency by remember { mutableStateOf(trade?.currency ?: defaultCurrency) }
    var date by remember { mutableStateOf(Dates.toLocalDate(trade?.tradedAt ?: now)) }
    var tickerError by remember { mutableStateOf<String?>(null) }
    var quantityError by remember { mutableStateOf<String?>(null) }
    var priceError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = stringResource(if (trade == null) R.string.trade_add_title else R.string.trade_edit_title),
        confirmLabel = stringResource(if (trade == null) R.string.trade_add_title else R.string.action_save),
        onConfirm = {
            val parsedQuantity = parseQuantity(quantity)
            val parsedPrice = parseQuantity(price)
            tickerError = if (ticker.isBlank()) "Введите тикер, например SBER" else null
            quantityError = if (parsedQuantity == null) "Введите количество больше нуля" else null
            priceError = if (parsedPrice == null) "Введите цену больше нуля" else null
            if (parsedQuantity != null && parsedPrice != null && tickerError == null) {
                val sameDay = trade?.takeIf { Dates.toLocalDate(it.tradedAt) == date }
                onSave(
                    TradeInput(
                        trade?.id, assetType, ticker, name, side, parsedQuantity, parsedPrice, Money.parse(fee) ?: BigDecimal.ZERO,
                        currency, sameDay?.tradedAt ?: if (date == Dates.toLocalDate(now)) now else Dates.noon(date),
                    )
                )
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (locked) NoticeBanner(stringResource(R.string.trade_locked), tone = Tone.WARNING, icon = Icons.Filled.Lock)
            SelectField(stringResource(R.string.trade_asset_type), types.firstOrNull { it.id == assetType }, types, Option::label, { assetType = it.id })
            AppTextField(
                ticker, { ticker = it.uppercase().take(30); tickerError = null }, stringResource(R.string.trade_ticker),
                error = tickerError, enabled = trade == null,
            )
            AppTextField(name, { name = it.take(150) }, stringResource(R.string.trade_name))
            val sides = listOf("buy" to R.string.trade_buy, "sell" to R.string.trade_sell)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sides.forEachIndexed { index, (value, title) ->
                    SegmentedButton(
                        selected = side == value, onClick = { side = value }, enabled = trade == null,
                        shape = SegmentedButtonDefaults.itemShape(index, sides.size), modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(title)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(
                    quantity, { quantity = quantityInput(it); quantityError = null }, stringResource(R.string.trade_quantity),
                    error = quantityError, enabled = !locked, keyboardType = KeyboardType.Decimal, alignEnd = true, modifier = Modifier.weight(1f),
                )
                AppTextField(
                    price, { price = quantityInput(it); priceError = null }, stringResource(R.string.trade_price),
                    error = priceError, enabled = !locked, keyboardType = KeyboardType.Decimal, alignEnd = true,
                    suffix = Money.symbol(currency), modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(fee, { fee = it }, stringResource(R.string.trade_fee), currency, Modifier.weight(1f))
                SelectField(stringResource(R.string.accounts_currency), currency, currencies, { it }, { currency = it }, Modifier.weight(1f))
            }
            DateField(stringResource(R.string.trade_date), date, { date = it })
        }
    }
}
