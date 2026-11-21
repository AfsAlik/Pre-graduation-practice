package ru.finhelper.app.data.remote

import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Программный интерфейс серверной части (REST, JSON, п. 4.1.2.1 ТЗ).
 *
 * Учетные данные (счета, транзакции, бюджеты, цели, сделки) передаются только
 * через [sync]; остальные методы обслуживают функции, требующие подключения к сети.
 */
interface FinHelperApi {
    // ------------------------------------------------------------ аутентификация
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): MessageResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("api/v1/auth/logout")
    suspend fun logout(): MessageResponse

    @POST("api/v1/auth/password/forgot")
    suspend fun forgotPassword(@Body body: EmailRequest): MessageResponse

    @POST("api/v1/auth/password/reset")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): MessageResponse

    @POST("api/v1/auth/password/change")
    suspend fun changePassword(@Body body: ChangePasswordRequest): MessageResponse

    @POST("api/v1/auth/2fa/setup")
    suspend fun setupTwoFactor(): TotpSetupResponse

    @POST("api/v1/auth/2fa/enable")
    suspend fun enableTwoFactor(@Body body: CodeRequest): MessageResponse

    @POST("api/v1/auth/2fa/disable")
    suspend fun disableTwoFactor(@Body body: CodeRequest): MessageResponse

    // ------------------------------------------------------------ профиль, уведомления, поддержка
    @GET("api/v1/profile")
    suspend fun profile(): ProfileDto

    @PATCH("api/v1/profile")
    suspend fun updateProfile(@Body body: ProfileUpdateRequest): ProfileDto

    @HTTP(method = "DELETE", path = "api/v1/profile", hasBody = true)
    suspend fun deleteProfile(@Body body: PasswordRequest): MessageResponse

    @POST("api/v1/profile/devices")
    suspend fun registerDevice(@Body body: DeviceRequest): MessageResponse

    @GET("api/v1/notifications")
    suspend fun notifications(@Query("limit") limit: Int = 50): NotificationsResponse

    @GET("api/v1/support/faq")
    suspend fun faq(): FaqResponse

    @POST("api/v1/support/requests")
    suspend fun supportRequest(@Body body: SupportRequest): MessageResponse

    @POST("api/v1/client-errors")
    suspend fun clientError(@Body body: ClientErrorRequest): MessageResponse

    // ------------------------------------------------------------ синхронизация
    @POST("api/v1/sync")
    suspend fun sync(@Body body: SyncRequest): SyncResponse

    // ------------------------------------------------------------ транзакции, импорт и экспорт
    @POST("api/v1/transactions/suggest-category")
    suspend fun suggestCategory(@Body body: TextRequest, @Query("kind") kind: String): SuggestionDto

    @Multipart
    @POST("api/v1/transactions/parse-receipt")
    suspend fun parseReceipt(@Part("text") text: RequestBody): ParsedTransactionDto

    @Multipart
    @POST("api/v1/import/statement")
    suspend fun importStatement(@Part("account_id") accountId: RequestBody, @Part file: MultipartBody.Part): ImportResultDto

    @Streaming
    @GET("api/v1/transactions/export")
    suspend fun exportTransactions(
        @Query("format") format: String,
        @Query("start") start: String,
        @Query("end") end: String,
    ): ResponseBody

    @Streaming
    @GET("api/v1/reports/export")
    suspend fun exportReport(
        @Query("format") format: String,
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("account_id") accountIds: List<String>,
        @Query("category_id") categoryIds: List<String>,
    ): ResponseBody

    @GET("api/v1/rates")
    suspend fun rates(): RatesDto

    // ------------------------------------------------------------ инвестиции
    @GET("api/v1/investments/portfolio")
    suspend fun portfolio(): PortfolioDto

    @GET("api/v1/investments/dynamics")
    suspend fun portfolioDynamics(@Query("period") period: String): DynamicsDto

    @GET("api/v1/investments/assets/{ticker}")
    suspend fun asset(@Path("ticker") ticker: String): AssetDto

    // ------------------------------------------------------------ рекомендации
    @GET("api/v1/recommendations")
    suspend fun recommendations(): RecommendationsDto

    @POST("api/v1/recommendations/{id}/feedback")
    suspend fun recommendationFeedback(@Path("id") id: String, @Body body: FeedbackRequest): RecommendationDto

    @POST("api/v1/recommendations/{id}/apply")
    suspend fun applyRecommendation(@Path("id") id: String): RecommendationDto

    @GET("api/v1/forecast")
    suspend fun forecast(): ForecastDto

    @GET("api/v1/risks")
    suspend fun risks(): RisksDto

    // ------------------------------------------------------------ администрирование
    @GET("api/v1/admin/users")
    suspend fun adminUsers(@Query("search") search: String?, @Query("limit") limit: Int = 200): UsersPageDto

    @POST("api/v1/admin/users")
    suspend fun adminCreateUser(@Body body: UserCreateRequest): AdminUserDto

    @POST("api/v1/admin/users/{id}/block")
    suspend fun adminBlockUser(@Path("id") id: String): AdminUserDto

    @POST("api/v1/admin/users/{id}/unblock")
    suspend fun adminUnblockUser(@Path("id") id: String): AdminUserDto

    @POST("api/v1/admin/users/{id}/reset-password")
    suspend fun adminResetPassword(@Path("id") id: String): AdminUserDto

    @GET("api/v1/admin/logs")
    suspend fun adminLogs(
        @Query("date_from") dateFrom: String?,
        @Query("date_to") dateTo: String?,
        @Query("user") user: String?,
        @Query("action") action: String?,
        @Query("limit") limit: Int = 200,
    ): LogsPageDto

    @Streaming
    @GET("api/v1/admin/logs/export")
    suspend fun adminExportLogs(
        @Query("date_from") dateFrom: String?,
        @Query("date_to") dateTo: String?,
        @Query("user") user: String?,
        @Query("action") action: String?,
    ): ResponseBody

    @Multipart
    @POST("api/v1/admin/logs/import")
    suspend fun adminImportLogs(@Part file: MultipartBody.Part): ImportLogsResultDto

    @GET("api/v1/admin/errors")
    suspend fun adminErrors(@Query("hours") hours: Int, @Query("limit") limit: Int = 20): ErrorsDto

    @GET("api/v1/admin/monitoring")
    suspend fun adminMonitoring(@Query("hours") hours: Int): MonitoringDto

    @GET("api/v1/admin/settings")
    suspend fun adminSettings(): Map<String, JsonObject>

    @PUT("api/v1/admin/settings/{key}")
    suspend fun adminPutSetting(@Path("key") key: String, @Body body: SettingRequest): MessageResponse

    // ------------------------------------------------------------ модели машинного обучения
    @GET("api/v1/ml/quality")
    suspend fun mlQuality(): QualityDto

    @GET("api/v1/ml/models")
    suspend fun mlModels(): ModelsDto

    @POST("api/v1/ml/models/train")
    suspend fun mlTrain(): MlModelDto

    @POST("api/v1/ml/models/{id}/activate")
    suspend fun mlActivate(@Path("id") id: String): MlModelDto

    @GET("api/v1/ml/training-data")
    suspend fun mlTrainingData(): TrainingDataDto
}
