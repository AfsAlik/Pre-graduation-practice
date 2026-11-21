package ru.finhelper.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.finhelper.app.data.remote.DecimalSerializer
import ru.finhelper.app.data.remote.InstantMillisSerializer

/**
 * Общие служебные поля записей, участвующих в синхронизации (п. 4.2.1.6 ТЗ).
 *
 * Классы сущностей одновременно описывают таблицы локальной базы данных Room и
 * формат JSON, в котором эти записи передаются серверу и принимаются от него.
 */
interface SyncedEntity {
    val id: String

    /** Момент создания записи, мс UTC. */
    val createdAt: Long

    /** Момент последнего изменения; по нему сервер разрешает конфликты. */
    val updatedAt: Long

    /** Момент удаления; null – запись действует. Удаленные записи сохраняются как отметки. */
    val deletedAt: Long?

    /** Номер ревизии сервера; 0 – запись еще не подтверждена сервером. */
    val rev: Long
}

/** Счет пользователя: наличные, банковский, кредитный или инвестиционный. */
@Serializable
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey override val id: String,
    val name: String,
    val type: String = "cash",
    val currency: String = "RUB",
    @SerialName("initial_balance") @Serializable(with = DecimalSerializer::class)
    val initialBalance: BigDecimal = BigDecimal.ZERO,
    @SerialName("is_archived") val isArchived: Boolean = false,
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) override val createdAt: Long = 0,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) override val updatedAt: Long = 0,
    @SerialName("deleted_at") @Serializable(with = InstantMillisSerializer::class) override val deletedAt: Long? = null,
    override val rev: Long = 0,
) : SyncedEntity

/** Категория или подкатегория доходов и расходов (системная либо пользовательская). */
@Serializable
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey override val id: String,
    val name: String,
    val kind: String = "expense",
    @SerialName("parent_id") val parentId: String? = null,
    val icon: String = "",
    val color: String = "",
    @SerialName("is_system") val isSystem: Boolean = false,
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) override val createdAt: Long = 0,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) override val updatedAt: Long = 0,
    @SerialName("deleted_at") @Serializable(with = InstantMillisSerializer::class) override val deletedAt: Long? = null,
    override val rev: Long = 0,
) : SyncedEntity

/** Операция: доход, расход или перевод между счетами. */
@Serializable
@Entity(tableName = "transactions", indices = [Index("occurredAt"), Index("accountId"), Index("categoryId")])
data class TransactionEntity(
    @PrimaryKey override val id: String,
    val kind: String,
    @Serializable(with = DecimalSerializer::class) val amount: BigDecimal,
    val currency: String = "RUB",
    @SerialName("account_id") val accountId: String,
    @SerialName("to_account_id") val toAccountId: String? = null,
    @SerialName("amount_to") @Serializable(with = DecimalSerializer::class) val amountTo: BigDecimal? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("occurred_at") @Serializable(with = InstantMillisSerializer::class) val occurredAt: Long,
    val comment: String = "",
    val merchant: String = "",
    /** Способ ввода: manual, template, receipt, voice, import, recurring. */
    val source: String = "manual",
    @SerialName("auto_categorized") val autoCategorized: Boolean = false,
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) override val createdAt: Long = 0,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) override val updatedAt: Long = 0,
    @SerialName("deleted_at") @Serializable(with = InstantMillisSerializer::class) override val deletedAt: Long? = null,
    override val rev: Long = 0,
) : SyncedEntity

/** Шаблон для быстрого ввода типовой операции. */
@Serializable
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey override val id: String,
    val name: String,
    val kind: String = "expense",
    @Serializable(with = DecimalSerializer::class) val amount: BigDecimal = BigDecimal.ZERO,
    val currency: String = "RUB",
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val comment: String = "",
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) override val createdAt: Long = 0,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) override val updatedAt: Long = 0,
    @SerialName("deleted_at") @Serializable(with = InstantMillisSerializer::class) override val deletedAt: Long? = null,
    override val rev: Long = 0,
) : SyncedEntity

/** Регулярный платеж с напоминанием о дате платежа. */
@Serializable
@Entity(tableName = "recurring_payments")
data class RecurringPaymentEntity(
    @PrimaryKey override val id: String,
    val name: String,
    @Serializable(with = DecimalSerializer::class) val amount: BigDecimal,
    val currency: String = "RUB",
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    /** Периодичность: week, month, year. */
    val period: String = "month",
    /** Дата следующего платежа в формате ГГГГ-ММ-ДД. */
    @SerialName("next_date") val nextDate: String,
    @SerialName("remind_days_before") val remindDaysBefore: Int = 1,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) override val createdAt: Long = 0,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) override val updatedAt: Long = 0,
    @SerialName("deleted_at") @Serializable(with = InstantMillisSerializer::class) override val deletedAt: Long? = null,
    override val rev: Long = 0,
) : SyncedEntity

/** Бюджетный лимит по категории расходов на неделю, месяц или год. */
@Serializable
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey override val id: String,
    @SerialName("category_id") val categoryId: String,
    val period: String = "month",
    @SerialName("limit_amount") @Serializable(with = DecimalSerializer::class) val limitAmount: BigDecimal,
    val currency: String = "RUB",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) override val createdAt: Long = 0,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) override val updatedAt: Long = 0,
    @SerialName("deleted_at") @Serializable(with = InstantMillisSerializer::class) override val deletedAt: Long? = null,
    override val rev: Long = 0,
) : SyncedEntity

/** Финансовая цель. */
@Serializable
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey override val id: String,
    val name: String,
    @SerialName("target_amount") @Serializable(with = DecimalSerializer::class) val targetAmount: BigDecimal,
    val currency: String = "RUB",
    /** Срок достижения в формате ГГГГ-ММ-ДД. */
    val deadline: String,
    /** Приоритет: low, medium, high. */
    val priority: String = "medium",
    /** Состояние: active или achieved (достигнутая цель доступна только для просмотра). */
    val status: String = "active",
    @SerialName("achieved_at") @Serializable(with = InstantMillisSerializer::class) val achievedAt: Long? = null,
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) override val createdAt: Long = 0,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) override val updatedAt: Long = 0,
    @SerialName("deleted_at") @Serializable(with = InstantMillisSerializer::class) override val deletedAt: Long? = null,
    override val rev: Long = 0,
) : SyncedEntity

/** Накопление (взнос) по финансовой цели. */
@Serializable
@Entity(tableName = "goal_contributions", indices = [Index("goalId")])
data class GoalContributionEntity(
    @PrimaryKey override val id: String,
    @SerialName("goal_id") val goalId: String,
    @Serializable(with = DecimalSerializer::class) val amount: BigDecimal,
    @SerialName("occurred_at") @Serializable(with = InstantMillisSerializer::class) val occurredAt: Long,
    val comment: String = "",
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) override val createdAt: Long = 0,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) override val updatedAt: Long = 0,
    @SerialName("deleted_at") @Serializable(with = InstantMillisSerializer::class) override val deletedAt: Long? = null,
    override val rev: Long = 0,
) : SyncedEntity

/** Сделка с инвестиционным инструментом (покупка или продажа). */
@Serializable
@Entity(tableName = "investment_trades", indices = [Index("ticker")])
data class TradeEntity(
    @PrimaryKey override val id: String,
    /** Тип актива: stock, bond, etf, currency, crypto. */
    @SerialName("asset_type") val assetType: String,
    val ticker: String,
    val name: String = "",
    val side: String = "buy",
    @Serializable(with = DecimalSerializer::class) val quantity: BigDecimal,
    @Serializable(with = DecimalSerializer::class) val price: BigDecimal,
    @Serializable(with = DecimalSerializer::class) val fee: BigDecimal = BigDecimal.ZERO,
    val currency: String = "RUB",
    @SerialName("traded_at") @Serializable(with = InstantMillisSerializer::class) val tradedAt: Long,
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) override val createdAt: Long = 0,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) override val updatedAt: Long = 0,
    @SerialName("deleted_at") @Serializable(with = InstantMillisSerializer::class) override val deletedAt: Long? = null,
    override val rev: Long = 0,
) : SyncedEntity

/**
 * Элемент очереди несинхронизированных операций (п. 4.2.1.6 д ТЗ).
 *
 * Идентификатор [opId] передается серверу и позволяет ему распознать повторно
 * присланную операцию, поэтому повтор отправки после обрыва связи не создает дубликатов.
 */
@Entity(tableName = "pending_operations", indices = [Index(value = ["opId"], unique = true), Index("entity", "entityId")])
data class PendingOperationEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val opId: String,
    val entity: String,
    val entityId: String,
    val updatedAt: Long,
    val deleted: Boolean,
    /** Данные записи в формате JSON (пусто для операции удаления). */
    val payload: String,
)

/** Сохраненный ответ сервера для просмотра без сети: портфель, курсы валют, рекомендации и т. п. */
@Entity(tableName = "cached_documents")
data class CachedDocumentEntity(
    @PrimaryKey val key: String,
    val json: String,
    val savedAt: Long,
)

/** Уведомление: полученное с сервера либо сформированное на устройстве. */
@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val read: Boolean = false,
)

/** Запись журнала ошибок клиентской части (п. 4.1.5.1 ТЗ). */
@Entity(tableName = "client_errors")
data class ClientErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val message: String,
    val context: String = "",
    /** Передана ли запись на сервер. */
    val sent: Boolean = false,
)
