package ru.finhelper.app.data.repo

import ru.finhelper.app.data.remote.AssetDto
import ru.finhelper.app.data.remote.DynamicsDto
import ru.finhelper.app.data.remote.FaqResponse
import ru.finhelper.app.data.remote.FeedbackRequest
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.ForecastDto
import ru.finhelper.app.data.remote.PortfolioDto
import ru.finhelper.app.data.remote.RatesDto
import ru.finhelper.app.data.remote.RecommendationDto
import ru.finhelper.app.data.remote.RecommendationsDto
import ru.finhelper.app.data.remote.RisksDto
import ru.finhelper.app.data.remote.apiCall

/**
 * Рыночные данные инвестиционного портфеля (п. 4.2.1.3 б, г, д ТЗ).
 *
 * Сделки хранятся в локальной базе; с сервера загружаются котировки, динамика
 * стоимости и история цен. Ответы сохраняются и доступны без сети.
 */
class InvestmentRepository(private val api: FinHelperApi, private val cache: CacheStore) {
    /** Обновляет рыночные цены: вызывается при каждом открытии портфеля (п. 4.2.2.15 ТЗ). */
    suspend fun refreshQuotes(): Result<Cached<PortfolioDto>> =
        cache.fetch(CacheStore.KEY_PORTFOLIO, PortfolioDto.serializer()) { api.portfolio() }

    /** Динамика стоимости портфеля за день, месяц или год. */
    suspend fun dynamics(period: String): Result<Cached<DynamicsDto>> =
        cache.fetch(CacheStore.dynamicsKey(period), DynamicsDto.serializer()) { api.portfolioDynamics(period) }

    /** История цен и доходность актива за разные периоды. */
    suspend fun asset(ticker: String): Result<Cached<AssetDto>> =
        cache.fetch(CacheStore.assetKey(ticker), AssetDto.serializer()) { api.asset(ticker) }
}

/** Рекомендации, прогноз и оценка рисков (п. 4.2.1.5 ТЗ): формируются сервером, сохраняются для просмотра без сети. */
class InsightsRepository(private val api: FinHelperApi, private val cache: CacheStore) {
    suspend fun recommendations(): Result<Cached<RecommendationsDto>> =
        cache.fetch(CacheStore.KEY_RECOMMENDATIONS, RecommendationsDto.serializer()) { api.recommendations() }

    suspend fun forecast(): Result<Cached<ForecastDto>> =
        cache.fetch(CacheStore.KEY_FORECAST, ForecastDto.serializer()) { api.forecast() }

    suspend fun risks(): Result<Cached<RisksDto>> = cache.fetch(CacheStore.KEY_RISKS, RisksDto.serializer()) { api.risks() }

    /** Явная обратная связь: оценка «Полезно» / «Не полезно». */
    suspend fun feedback(id: String, useful: Boolean): Result<RecommendationDto> =
        apiCall { api.recommendationFeedback(id, FeedbackRequest(useful)) }

    /** Применение рекомендации (неявная обратная связь), например установка бюджета. */
    suspend fun apply(id: String): Result<RecommendationDto> = apiCall { api.applyRecommendation(id) }
}

/** Справочные данные: курсы валют и ответы на частые вопросы. */
class ReferenceRepository(private val api: FinHelperApi, private val cache: CacheStore) {
    /** Загружает курсы валют; при недоступности сервера остаются сохраненные курсы. */
    suspend fun refreshRates(): Result<Cached<RatesDto>> = cache.fetch(CacheStore.KEY_RATES, RatesDto.serializer()) { api.rates() }

    suspend fun faq(): Result<Cached<FaqResponse>> = cache.fetch(CacheStore.KEY_FAQ, FaqResponse.serializer()) { api.faq() }
}
