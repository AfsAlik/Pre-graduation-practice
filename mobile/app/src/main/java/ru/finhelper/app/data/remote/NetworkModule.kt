package ru.finhelper.app.data.remote

import kotlinx.serialization.encodeToString
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.finhelper.app.data.prefs.SessionStore
import java.util.concurrent.TimeUnit

/**
 * Создание клиента программного интерфейса сервера.
 *
 * @param baseUrl поставщик текущего адреса сервера (адрес изменяется на экране входа)
 * @param onSessionExpired вызывается, когда сервер отклонил токен обновления
 */
class NetworkModule(
    private val baseUrl: () -> String,
    private val session: SessionStore,
    private val onSessionExpired: () -> Unit,
    private val logging: Boolean = false,
) {
    private val jsonMediaType = "application/json".toMediaType()

    /** Клиент без авторизации: используется только для обновления токена. */
    private val plainClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Подставляет в запрос актуальный адрес сервера: схему, узел и порт. */
    private val baseUrlInterceptor = Interceptor { chain ->
        val target = baseUrl().toHttpUrlOrNull()
        val request = chain.request()
        if (target == null) {
            chain.proceed(request)
        } else {
            val url = request.url.newBuilder().scheme(target.scheme).host(target.host).port(target.port).build()
            chain.proceed(request.newBuilder().url(url).build())
        }
    }

    /** Добавляет заголовок Authorization: Bearer <токен доступа>. */
    private val authInterceptor = Interceptor { chain ->
        val token = session.accessToken
        val request = chain.request()
        if (token == null || isAuthPath(request)) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header(AUTHORIZATION, "Bearer $token").build())
        }
    }

    /**
     * При ответе 401 обновляет токен доступа запросом POST /auth/refresh и повторяет
     * исходный запрос. Обновление выполняется под блокировкой: параллельные запросы
     * не расходуют одноразовый токен обновления дважды.
     */
    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            val request = response.request
            if (isAuthPath(request) || response.priorResponse != null) return null
            synchronized(this) {
                val current = session.accessToken
                val sent = request.header(AUTHORIZATION)?.removePrefix("Bearer ")
                // Токен уже обновлен другим запросом – достаточно повторить с новым
                val token = if (current != null && current != sent) current else refresh() ?: return null
                return request.newBuilder().header(AUTHORIZATION, "Bearer $token").build()
            }
        }
    }

    private fun refresh(): String? {
        val refreshToken = session.refreshToken ?: return null
        val url = baseUrl().toHttpUrlOrNull()?.newBuilder()?.addPathSegments("api/v1/auth/refresh")?.build() ?: return null
        val body = AppJson.encodeToString(RefreshRequest(refreshToken)).toRequestBody(jsonMediaType)
        return try {
            plainClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val tokens = AppJson.decodeFromString<TokenResponse>(response.body?.string().orEmpty())
                        session.updateTokens(tokens.accessToken, tokens.refreshToken, tokens.role)
                        tokens.accessToken
                    }
                    response.code == 401 -> {
                        onSessionExpired()
                        null
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            null // нет сети: сеанс сохраняется, запрос будет повторен позже
        }
    }

    private fun isAuthPath(request: Request): Boolean {
        val path = request.url.encodedPath
        return AUTH_PATHS.any { path.endsWith(it) }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(baseUrlInterceptor)
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .apply {
            if (logging) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    val api: FinHelperApi = Retrofit.Builder()
        .baseUrl("http://localhost/") // заменяется перехватчиком baseUrlInterceptor
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory(jsonMediaType))
        .build()
        .create(FinHelperApi::class.java)

    private companion object {
        const val AUTHORIZATION = "Authorization"

        /** Запросы, выполняемые без токена доступа; ответ 401 для них не означает истечение сеанса. */
        val AUTH_PATHS = listOf(
            "/auth/login", "/auth/register", "/auth/refresh", "/auth/password/forgot", "/auth/password/reset",
        )
    }
}
