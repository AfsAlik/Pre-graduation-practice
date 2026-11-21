package ru.finhelper.app.domain

import java.math.BigDecimal
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.TransactionEntity

/** Расчет балансов счетов (п. 4.2.1.1 е ТЗ). */
object BalanceCalculator {
    const val INCOME = "income"
    const val EXPENSE = "expense"
    const val TRANSFER = "transfer"

    /** Типы счетов, средства на которых считаются ликвидными (наличные и банковские). */
    private val LIQUID_TYPES = setOf("cash", "bank")

    /**
     * Баланс каждого счета: начальный остаток плюс доходы, минус расходы и
     * исходящие переводы, плюс входящие переводы.
     */
    fun accountBalances(accounts: List<AccountEntity>, transactions: List<TransactionEntity>): Map<String, BigDecimal> {
        val balances = accounts.associate { it.id to it.initialBalance }.toMutableMap()
        for (transaction in transactions) {
            if (transaction.deletedAt != null) continue
            balances.computeIfPresent(transaction.accountId) { _, value ->
                if (transaction.kind == INCOME) value + transaction.amount else value - transaction.amount
            }
            if (transaction.kind == TRANSFER && transaction.toAccountId != null) {
                balances.computeIfPresent(transaction.toAccountId) { _, value ->
                    value + (transaction.amountTo ?: transaction.amount)
                }
            }
        }
        return balances
    }

    /** Общий баланс неархивных счетов в базовой валюте пользователя. */
    fun totalBalance(
        accounts: List<AccountEntity>,
        balances: Map<String, BigDecimal>,
        converter: CurrencyConverter,
        baseCurrency: String,
        liquidOnly: Boolean = false,
    ): BigDecimal = accounts
        .filter { !it.isArchived && (!liquidOnly || it.type in LIQUID_TYPES) }
        .fold(BigDecimal.ZERO) { total, account ->
            total + converter.convert(balances[account.id] ?: BigDecimal.ZERO, account.currency, baseCurrency)
        }
}
