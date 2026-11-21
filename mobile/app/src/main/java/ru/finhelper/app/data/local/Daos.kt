package ru.finhelper.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Операции, одинаковые для всех синхронизируемых таблиц. */
interface SyncedDao<T : SyncedEntity> {
    @Upsert
    suspend fun upsert(item: T)

    @Upsert
    suspend fun upsertAll(items: List<T>)
}

@Dao
interface AccountDao : SyncedDao<AccountEntity> {
    @Query("SELECT * FROM accounts WHERE deletedAt IS NULL ORDER BY createdAt")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Query("SELECT id FROM accounts")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM accounts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface CategoryDao : SyncedDao<CategoryEntity> {
    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY isSystem DESC, name")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    /** Системные категории общие для всех пользователей и при полной сверке не удаляются. */
    @Query("SELECT id FROM categories WHERE isSystem = 0")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface TransactionDao : SyncedDao<TransactionEntity> {
    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY occurredAt DESC, id")
    fun observeActive(): Flow<List<TransactionEntity>>

    /** Корзина: удаленные операции, которые еще можно восстановить (п. 4.2.1.1 и ТЗ). */
    @Query("SELECT * FROM transactions WHERE deletedAt IS NOT NULL AND deletedAt >= :since ORDER BY deletedAt DESC")
    fun observeDeleted(since: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: String): TransactionEntity?

    @Query("DELETE FROM transactions WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE deletedAt IS NULL AND (accountId = :accountId OR toAccountId = :accountId)")
    suspend fun countForAccount(accountId: String): Int

    @Query("SELECT id FROM transactions")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface TemplateDao : SyncedDao<TemplateEntity> {
    @Query("SELECT * FROM templates WHERE deletedAt IS NULL ORDER BY name")
    fun observeActive(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun byId(id: String): TemplateEntity?

    @Query("SELECT id FROM templates")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM templates WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface RecurringPaymentDao : SyncedDao<RecurringPaymentEntity> {
    @Query("SELECT * FROM recurring_payments WHERE deletedAt IS NULL ORDER BY nextDate")
    fun observeActive(): Flow<List<RecurringPaymentEntity>>

    @Query("SELECT * FROM recurring_payments WHERE deletedAt IS NULL AND isActive = 1")
    suspend fun active(): List<RecurringPaymentEntity>

    @Query("SELECT * FROM recurring_payments WHERE id = :id")
    suspend fun byId(id: String): RecurringPaymentEntity?

    @Query("SELECT id FROM recurring_payments")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM recurring_payments WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface BudgetDao : SyncedDao<BudgetEntity> {
    @Query("SELECT * FROM budgets WHERE deletedAt IS NULL ORDER BY createdAt")
    fun observeActive(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun byId(id: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE deletedAt IS NULL AND categoryId = :categoryId AND period = :period LIMIT 1")
    suspend fun find(categoryId: String, period: String): BudgetEntity?

    @Query("SELECT id FROM budgets")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM budgets WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface GoalDao : SyncedDao<GoalEntity> {
    @Query("SELECT * FROM goals WHERE deletedAt IS NULL ORDER BY status, deadline")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun byId(id: String): GoalEntity?

    @Query("SELECT id FROM goals")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM goals WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface GoalContributionDao : SyncedDao<GoalContributionEntity> {
    @Query("SELECT * FROM goal_contributions WHERE deletedAt IS NULL ORDER BY occurredAt")
    fun observeActive(): Flow<List<GoalContributionEntity>>

    @Query("SELECT * FROM goal_contributions WHERE deletedAt IS NULL AND goalId = :goalId ORDER BY occurredAt")
    suspend fun forGoal(goalId: String): List<GoalContributionEntity>

    @Query("SELECT id FROM goal_contributions")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM goal_contributions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface TradeDao : SyncedDao<TradeEntity> {
    @Query("SELECT * FROM investment_trades WHERE deletedAt IS NULL ORDER BY tradedAt DESC")
    fun observeActive(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM investment_trades WHERE id = :id")
    suspend fun byId(id: String): TradeEntity?

    @Query("SELECT * FROM investment_trades WHERE deletedAt IS NULL AND ticker = :ticker")
    suspend fun forTicker(ticker: String): List<TradeEntity>

    @Query("SELECT id FROM investment_trades")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM investment_trades WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

/** Очередь несинхронизированных операций. */
@Dao
interface PendingOperationDao {
    @Insert
    suspend fun insert(operation: PendingOperationEntity)

    /** Операции в порядке их появления. */
    @Query("SELECT * FROM pending_operations ORDER BY seq LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations ORDER BY seq")
    suspend fun all(): List<PendingOperationEntity>

    @Query("SELECT COUNT(*) FROM pending_operations")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_operations")
    suspend fun count(): Int

    @Query("DELETE FROM pending_operations WHERE entity = :entity AND entityId = :entityId")
    suspend fun deleteForEntity(entity: String, entityId: String)

    @Query("DELETE FROM pending_operations WHERE opId IN (:opIds)")
    suspend fun deleteByOpIds(opIds: List<String>)
}

@Dao
interface CachedDocumentDao {
    @Upsert
    suspend fun put(document: CachedDocumentEntity)

    @Query("SELECT * FROM cached_documents WHERE `key` = :key")
    suspend fun get(key: String): CachedDocumentEntity?

    @Query("SELECT * FROM cached_documents WHERE `key` = :key")
    fun observe(key: String): Flow<CachedDocumentEntity?>
}

@Dao
interface NotificationDao {
    @Upsert
    suspend fun upsert(item: NotificationEntity)

    @Query("SELECT * FROM notifications ORDER BY createdAt DESC LIMIT 200")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT id FROM notifications")
    suspend fun allIds(): List<String>

    @Query("UPDATE notifications SET read = 1 WHERE read = 0")
    suspend fun markAllRead()
}

@Dao
interface ClientErrorDao {
    @Insert
    suspend fun insert(item: ClientErrorEntity)

    @Query("SELECT * FROM client_errors WHERE sent = 0 ORDER BY id LIMIT 50")
    suspend fun unsent(): List<ClientErrorEntity>

    @Query("UPDATE client_errors SET sent = 1 WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>)

    @Query("DELETE FROM client_errors WHERE at < :before")
    suspend fun deleteOlderThan(before: Long)
}
