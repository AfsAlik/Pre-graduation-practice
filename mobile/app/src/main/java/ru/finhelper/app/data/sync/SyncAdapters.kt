package ru.finhelper.app.data.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.AppDatabase
import ru.finhelper.app.data.local.BudgetEntity
import ru.finhelper.app.data.local.CategoryEntity
import ru.finhelper.app.data.local.GoalContributionEntity
import ru.finhelper.app.data.local.GoalEntity
import ru.finhelper.app.data.local.RecurringPaymentEntity
import ru.finhelper.app.data.local.SyncedEntity
import ru.finhelper.app.data.local.TemplateEntity
import ru.finhelper.app.data.local.TradeEntity
import ru.finhelper.app.data.local.TransactionEntity
import ru.finhelper.app.data.remote.AppJson

/**
 * Описание синхронизируемой сущности: имя в протоколе синхронизации, правила
 * преобразования в JSON и операции с ее таблицей.
 *
 * @property entity имя сущности в запросе POST /sync
 * @property payloadKeys поля, которые сервер принимает от устройства; служебные
 * поля (ревизия, признак системной категории и т. п.) в очередь не попадают
 */
class SyncAdapter<T : SyncedEntity>(
    val entity: String,
    private val serializer: KSerializer<T>,
    private val payloadKeys: Set<String>,
    val upsert: suspend (T) -> Unit,
    val upsertAll: suspend (List<T>) -> Unit,
    val allIds: suspend () -> List<String>,
    val deleteByIds: suspend (List<String>) -> Unit,
) {
    /** Данные записи для передачи серверу. */
    fun payload(item: T): JsonObject =
        JsonObject(AppJson.encodeToJsonElement(serializer, item).jsonObject.filterKeys { it in payloadKeys })

    /** Запись, полученная от сервера. */
    fun decode(json: JsonObject): T = AppJson.decodeFromJsonElement(serializer, json)
}

/** Адаптеры всех синхронизируемых сущностей в порядке, в котором сервер применяет операции. */
class SyncAdapters(db: AppDatabase) {
    val account = SyncAdapter(
        "account", AccountEntity.serializer(), setOf("name", "type", "currency", "initial_balance", "is_archived"),
        db.accounts()::upsert, db.accounts()::upsertAll, db.accounts()::allIds, db.accounts()::deleteByIds,
    )
    val category = SyncAdapter(
        "category", CategoryEntity.serializer(), setOf("name", "kind", "parent_id", "icon", "color"),
        db.categories()::upsert, db.categories()::upsertAll, db.categories()::allIds, db.categories()::deleteByIds,
    )
    val template = SyncAdapter(
        "template", TemplateEntity.serializer(),
        setOf("name", "kind", "amount", "currency", "account_id", "category_id", "comment"),
        db.templates()::upsert, db.templates()::upsertAll, db.templates()::allIds, db.templates()::deleteByIds,
    )
    val recurringPayment = SyncAdapter(
        "recurring_payment", RecurringPaymentEntity.serializer(),
        setOf("name", "amount", "currency", "account_id", "category_id", "period", "next_date", "remind_days_before", "is_active"),
        db.recurringPayments()::upsert, db.recurringPayments()::upsertAll,
        db.recurringPayments()::allIds, db.recurringPayments()::deleteByIds,
    )
    val transaction = SyncAdapter(
        "transaction", TransactionEntity.serializer(),
        setOf(
            "kind", "amount", "currency", "account_id", "to_account_id", "amount_to", "category_id",
            "occurred_at", "comment", "merchant", "source",
        ),
        db.transactions()::upsert, db.transactions()::upsertAll, db.transactions()::allIds, db.transactions()::deleteByIds,
    )
    val budget = SyncAdapter(
        "budget", BudgetEntity.serializer(), setOf("category_id", "period", "limit_amount", "currency", "is_active"),
        db.budgets()::upsert, db.budgets()::upsertAll, db.budgets()::allIds, db.budgets()::deleteByIds,
    )
    val goal = SyncAdapter(
        "goal", GoalEntity.serializer(), setOf("name", "target_amount", "currency", "deadline", "priority"),
        db.goals()::upsert, db.goals()::upsertAll, db.goals()::allIds, db.goals()::deleteByIds,
    )
    val goalContribution = SyncAdapter(
        "goal_contribution", GoalContributionEntity.serializer(), setOf("goal_id", "amount", "occurred_at", "comment"),
        db.goalContributions()::upsert, db.goalContributions()::upsertAll,
        db.goalContributions()::allIds, db.goalContributions()::deleteByIds,
    )
    val trade = SyncAdapter(
        "investment_trade", TradeEntity.serializer(),
        setOf("asset_type", "ticker", "name", "side", "quantity", "price", "fee", "currency", "traded_at"),
        db.trades()::upsert, db.trades()::upsertAll, db.trades()::allIds, db.trades()::deleteByIds,
    )

    val all: List<SyncAdapter<out SyncedEntity>> = listOf(
        account, category, template, recurringPayment, transaction, budget, goal, goalContribution, trade,
    )
}
