package ru.finhelper.app.data.repo

import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.BudgetEntity
import ru.finhelper.app.data.local.CategoryEntity
import ru.finhelper.app.data.local.GoalContributionEntity
import ru.finhelper.app.data.local.GoalEntity
import ru.finhelper.app.data.local.TradeEntity
import ru.finhelper.app.data.local.TransactionEntity
import ru.finhelper.app.data.remote.ProfileDto
import ru.finhelper.app.domain.BalanceCalculator
import ru.finhelper.app.domain.CurrencyConverter
import java.math.BigDecimal

/**
 * Согласованный срез учетных данных пользователя из локальной базы.
 *
 * Все разделы приложения строят свое состояние по одному и тому же срезу, поэтому
 * показатели на разных экранах не расходятся (п. 4.1.2.3 ТЗ).
 */
data class FinanceSnapshot(
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val goals: List<GoalEntity> = emptyList(),
    val contributions: List<GoalContributionEntity> = emptyList(),
    val trades: List<TradeEntity> = emptyList(),
    /** Последние известные рыночные цены активов по тикеру. */
    val prices: Map<String, BigDecimal> = emptyMap(),
    /** Момент получения цен с сервера, мс; 0 – цены не загружались. */
    val pricesAt: Long = 0,
    val converter: CurrencyConverter = CurrencyConverter.FALLBACK,
    val profile: ProfileDto = ProfileDto(),
    /** false – данные еще читаются из базы. */
    val loaded: Boolean = false,
) {
    val baseCurrency: String get() = profile.baseCurrency
    val categoryMap: Map<String, CategoryEntity> by lazy { categories.associateBy { it.id } }
    val accountMap: Map<String, AccountEntity> by lazy { accounts.associateBy { it.id } }
    val balances: Map<String, BigDecimal> by lazy { BalanceCalculator.accountBalances(accounts, transactions) }
}
