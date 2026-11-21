package ru.finhelper.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

// Модели запросов и ответов программного интерфейса серверной части (/api/v1).
// Имена полей JSON соответствуют спецификации OpenAPI сервера.

// ---------------------------------------------------------------- аутентификация
@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("consent_personal_data") val consentPersonalData: Boolean,
)

@Serializable
data class LoginRequest(val email: String, val password: String, @SerialName("totp_code") val totpCode: String? = null)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val role: String = "user",
    @SerialName("user_id") val userId: String = "",
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class EmailRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val token: String, @SerialName("new_password") val newPassword: String)

@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class CodeRequest(val code: String)

@Serializable
data class PasswordRequest(val password: String)

@Serializable
data class TotpSetupResponse(val secret: String, val uri: String = "")

@Serializable
data class MessageResponse(val message: String = "")

// ---------------------------------------------------------------- профиль
/** Настройки уведомлений: каналы, порог бюджета, частота и виды уведомлений (п. 4.2.1.6 в ТЗ). */
@Serializable
data class NotificationSettingsDto(
    @SerialName("push_enabled") val pushEnabled: Boolean = true,
    @SerialName("email_enabled") val emailEnabled: Boolean = false,
    @SerialName("budget_threshold_percent") val budgetThresholdPercent: Int = 80,
    val frequency: String = "instant",
    val budget: Boolean = true,
    val recurring: Boolean = true,
    val goals: Boolean = true,
    val recommendations: Boolean = true,
    val risks: Boolean = true,
)

@Serializable
data class ProfileDto(
    val id: String = "",
    val email: String = "",
    @SerialName("display_name") val displayName: String = "",
    val role: String = "user",
    @SerialName("base_currency") val baseCurrency: String = "RUB",
    @SerialName("risk_profile") val riskProfile: String = "moderate",
    @SerialName("invest_horizon_years") val investHorizonYears: Int = 5,
    val theme: String = "system",
    @SerialName("costing_method") val costingMethod: String = "average",
    @SerialName("onboarding_done") val onboardingDone: Boolean = false,
    @SerialName("totp_enabled") val totpEnabled: Boolean = false,
    @SerialName("notification_settings") val notificationSettings: NotificationSettingsDto = NotificationSettingsDto(),
)

@Serializable
data class ProfileUpdateRequest(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("base_currency") val baseCurrency: String? = null,
    @SerialName("risk_profile") val riskProfile: String? = null,
    @SerialName("invest_horizon_years") val investHorizonYears: Int? = null,
    val theme: String? = null,
    @SerialName("costing_method") val costingMethod: String? = null,
    @SerialName("onboarding_done") val onboardingDone: Boolean? = null,
    @SerialName("notification_settings") val notificationSettings: NotificationSettingsDto? = null,
)

@Serializable
data class DeviceRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("push_token") val pushToken: String = "",
    val model: String = "",
)

@Serializable
data class NotificationDto(
    val id: String,
    val kind: String = "",
    val title: String = "",
    val body: String = "",
    @SerialName("created_at") @Serializable(with = InstantMillisSerializer::class) val createdAt: Long = 0,
    val read: Boolean = false,
)

@Serializable
data class NotificationsResponse(val items: List<NotificationDto> = emptyList())

@Serializable
data class FaqItem(val question: String, val answer: String)

@Serializable
data class FaqResponse(val items: List<FaqItem> = emptyList())

@Serializable
data class SupportRequest(val subject: String, val message: String)

@Serializable
data class ClientErrorRequest(val kind: String, val message: String, val level: String = "error", val context: Map<String, String> = emptyMap())

// ---------------------------------------------------------------- синхронизация
/** Одна операция очереди несинхронизированных операций. */
@Serializable
data class SyncChangeDto(
    @SerialName("op_id") val opId: String,
    val entity: String,
    val id: String,
    @SerialName("updated_at") @Serializable(with = InstantMillisSerializer::class) val updatedAt: Long,
    val deleted: Boolean = false,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SyncRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("since_rev") val sinceRev: Long,
    val changes: List<SyncChangeDto> = emptyList(),
)

/** Результат обработки операции сервером: applied, duplicate, conflict или rejected. */
@Serializable
data class SyncResultDto(
    @SerialName("op_id") val opId: String,
    val entity: String = "",
    val id: String = "",
    val status: String,
    val message: String = "",
)

@Serializable
data class SyncResponse(
    val rev: Long,
    val results: List<SyncResultDto> = emptyList(),
    /** Изменения на сервере после ревизии since_rev: вид сущности -> записи. */
    val changes: Map<String, List<JsonObject>> = emptyMap(),
)

// ---------------------------------------------------------------- транзакции
@Serializable
data class TextRequest(val text: String)

@Serializable
data class SuggestionDto(
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    val confidence: Double? = null,
)

@Serializable
data class ParsedTransactionDto(
    val kind: String = "expense",
    @Serializable(with = DecimalSerializer::class) val amount: BigDecimal? = null,
    @SerialName("occurred_at") @Serializable(with = InstantMillisSerializer::class) val occurredAt: Long? = null,
    val merchant: String = "",
    val category: SuggestionDto = SuggestionDto(),
)

@Serializable
data class ImportResultDto(
    val total: Int = 0,
    val imported: Int = 0,
    @SerialName("skipped_duplicates") val skippedDuplicates: Int = 0,
)

/** Курсы валют к рублю с датой актуальности (п. 4.2.4.4 ТЗ). */
@Serializable
data class RatesDto(
    val rates: Map<String, String> = emptyMap(),
    @SerialName("as_of") val asOf: String? = null,
    val stale: Boolean = false,
)

// ---------------------------------------------------------------- инвестиции
@Serializable
data class PositionDto(
    val ticker: String,
    val name: String = "",
    @SerialName("asset_type") val assetType: String = "stock",
    val currency: String = "RUB",
    @SerialName("current_price") @Serializable(with = DecimalSerializer::class) val currentPrice: BigDecimal? = null,
)

@Serializable
data class PortfolioDto(
    val positions: List<PositionDto> = emptyList(),
    @SerialName("quotes_as_of") @Serializable(with = InstantMillisSerializer::class) val quotesAsOf: Long? = null,
    @SerialName("quotes_stale") val quotesStale: Boolean = false,
)

@Serializable
data class ValuePointDto(val date: String, @Serializable(with = DecimalSerializer::class) val value: BigDecimal)

@Serializable
data class DynamicsDto(val period: String = "month", val currency: String = "RUB", val points: List<ValuePointDto> = emptyList())

@Serializable
data class PricePointDto(val date: String, @Serializable(with = DecimalSerializer::class) val price: BigDecimal)

@Serializable
data class AssetDto(
    val ticker: String,
    val name: String = "",
    @SerialName("asset_type") val assetType: String = "",
    @SerialName("current_price") @Serializable(with = DecimalSerializer::class) val currentPrice: BigDecimal? = null,
    @SerialName("price_as_of") @Serializable(with = InstantMillisSerializer::class) val priceAsOf: Long? = null,
    val history: List<PricePointDto> = emptyList(),
    /** Доходность за неделю, месяц, квартал и год, %. */
    val returns: Map<String, Double?> = emptyMap(),
)

// ---------------------------------------------------------------- рекомендации
@Serializable
data class RecommendationDto(
    val id: String,
    val kind: String = "",
    val title: String = "",
    val text: String = "",
    val explanation: String = "",
    @SerialName("estimated_saving") @Serializable(with = DecimalSerializer::class) val estimatedSaving: BigDecimal = BigDecimal.ZERO,
    /** Связанное действие, например {"type": "set_budget", "label": "Установить бюджет"}. */
    val action: JsonObject = JsonObject(emptyMap()),
    val status: String = "new",
    val feedback: String? = null,
)

@Serializable
data class RecommendationsDto(val currency: String = "RUB", val items: List<RecommendationDto> = emptyList())

@Serializable
data class FeedbackRequest(val useful: Boolean)

/** Прогноз показателя на следующий месяц с границами интервала уверенности. */
@Serializable
data class ForecastValueDto(val forecast: Double = 0.0, val lower: Double = 0.0, val upper: Double = 0.0, val mape: Double? = null)

@Serializable
data class ForecastDto(
    val month: String = "",
    val currency: String = "RUB",
    val confidence: Double = 0.95,
    val expense: ForecastValueDto = ForecastValueDto(),
    val income: ForecastValueDto = ForecastValueDto(),
)

@Serializable
data class RiskDto(val id: String, val kind: String = "", val severity: String = "warning", val message: String = "")

@Serializable
data class RisksDto(val items: List<RiskDto> = emptyList())

// ---------------------------------------------------------------- администрирование
@Serializable
data class AdminUserDto(
    val id: String,
    val email: String = "",
    @SerialName("display_name") val displayName: String = "",
    val role: String = "user",
    @SerialName("role_title") val roleTitle: String = "",
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("last_login_at") @Serializable(with = InstantMillisSerializer::class) val lastLoginAt: Long? = null,
    @SerialName("temporary_password") val temporaryPassword: String? = null,
)

@Serializable
data class UsersPageDto(val total: Int = 0, val items: List<AdminUserDto> = emptyList())

@Serializable
data class UserCreateRequest(
    val email: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    val password: String? = null,
)

@Serializable
data class AuditLogDto(
    val id: Long = 0,
    @Serializable(with = InstantMillisSerializer::class) val at: Long = 0,
    val journal: String = "",
    @SerialName("user_email") val userEmail: String = "",
    val action: String = "",
    val entity: String = "",
    val ip: String = "",
    val origin: String = "system",
)

@Serializable
data class LogsPageDto(val total: Int = 0, val items: List<AuditLogDto> = emptyList())

@Serializable
data class ImportLogsResultDto(val imported: Int = 0)

@Serializable
data class AvailabilityPointDto(val hour: String, val percent: Double)

@Serializable
data class MonitoringDto(
    @SerialName("period_hours") val periodHours: Int = 24,
    @SerialName("availability_percent") val availabilityPercent: Double = 100.0,
    val requests: Long = 0,
    @SerialName("server_errors") val serverErrors: Long = 0,
    @SerialName("logged_errors") val loggedErrors: Long = 0,
    @SerialName("average_response_ms") val averageResponseMs: Double = 0.0,
    @SerialName("max_response_ms") val maxResponseMs: Double = 0.0,
    val components: Map<String, Boolean> = emptyMap(),
    @SerialName("last_check_at") @Serializable(with = InstantMillisSerializer::class) val lastCheckAt: Long? = null,
    @SerialName("availability_chart") val availabilityChart: List<AvailabilityPointDto> = emptyList(),
)

@Serializable
data class ErrorLogDto(
    val id: Long = 0,
    @Serializable(with = InstantMillisSerializer::class) val at: Long = 0,
    val source: String = "",
    val level: String = "",
    val kind: String = "",
    val message: String = "",
)

@Serializable
data class ErrorsDto(val items: List<ErrorLogDto> = emptyList())

@Serializable
data class SettingRequest(val value: JsonObject)

// ---------------------------------------------------------------- модели машинного обучения
@Serializable
data class MlModelDto(
    val id: String,
    val version: Int = 0,
    /** Состояние: training, ready или failed. */
    val status: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    val samples: Int = 0,
    val accuracy: Double = 0.0,
    val precision: Double = 0.0,
    val recall: Double = 0.0,
    val f1: Double = 0.0,
    @SerialName("started_at") @Serializable(with = InstantMillisSerializer::class) val startedAt: Long = 0,
    @SerialName("trained_at") @Serializable(with = InstantMillisSerializer::class) val trainedAt: Long? = null,
    val error: String = "",
)

@Serializable
data class ModelsDto(val items: List<MlModelDto> = emptyList())

@Serializable
data class QualityPointDto(
    val month: String,
    val accuracy: Double = 0.0,
    val precision: Double = 0.0,
    val recall: Double = 0.0,
    val f1: Double = 0.0,
    @SerialName("forecast_mape") val forecastMape: Double? = null,
    @SerialName("positive_feedback_share") val positiveFeedbackShare: Double? = null,
)

@Serializable
data class QualityDto(
    val model: MlModelDto? = null,
    @SerialName("min_accuracy") val minAccuracy: Double = 0.8,
    @SerialName("forecast_mape") val forecastMape: Double? = null,
    @SerialName("positive_feedback_share") val positiveFeedbackShare: Double = 0.0,
    @SerialName("rated_recommendations") val ratedRecommendations: Int = 0,
    @SerialName("applied_recommendations") val appliedRecommendations: Int = 0,
    @SerialName("training_samples") val trainingSamples: Int = 0,
    val history: List<QualityPointDto> = emptyList(),
)

@Serializable
data class TrainingDataDto(val total: Int = 0, @SerialName("by_category") val byCategory: Map<String, Int> = emptyMap())
