package ru.finhelper.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.math.BigDecimal

/** Преобразование десятичных чисел для хранения в SQLite: значение записывается текстом без потери точности. */
class Converters {
    @TypeConverter
    fun decimalToText(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun textToDecimal(value: String?): BigDecimal? = value?.toBigDecimalOrNull()
}

/**
 * Локальная база данных приложения – единственный источник данных для экранов
 * (п. 4.3.2.1 а ТЗ). Файл базы шифруется средствами SQLCipher, см. [EncryptedDatabaseFactory].
 */
@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, TransactionEntity::class, TemplateEntity::class,
        RecurringPaymentEntity::class, BudgetEntity::class, GoalEntity::class,
        GoalContributionEntity::class, TradeEntity::class, PendingOperationEntity::class,
        CachedDocumentEntity::class, NotificationEntity::class, ClientErrorEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun transactions(): TransactionDao
    abstract fun templates(): TemplateDao
    abstract fun recurringPayments(): RecurringPaymentDao
    abstract fun budgets(): BudgetDao
    abstract fun goals(): GoalDao
    abstract fun goalContributions(): GoalContributionDao
    abstract fun trades(): TradeDao
    abstract fun pendingOperations(): PendingOperationDao
    abstract fun cachedDocuments(): CachedDocumentDao
    abstract fun notifications(): NotificationDao
    abstract fun clientErrors(): ClientErrorDao
}
