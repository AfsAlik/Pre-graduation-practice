package ru.finhelper.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.finhelper.app.data.prefs.SecureStore
import ru.finhelper.app.data.prefs.Session
import ru.finhelper.app.data.prefs.SessionStore
import ru.finhelper.app.data.remote.ApiError
import ru.finhelper.app.data.remote.LoginRequest
import ru.finhelper.app.data.remote.NetworkModule
import ru.finhelper.app.data.remote.apiCall

/** Проверка сетевого слоя на локальном учебном сервере: авторизация, обновление токена, ошибки. */
@RunWith(RobolectricTestRunner::class)
class NetworkModuleTest {
    private val server = MockWebServer()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session = SessionStore(SecureStore(context.getSharedPreferences("test-secure", Context.MODE_PRIVATE), PlainCipher))
    private var sessionExpired = false
    private lateinit var network: NetworkModule

    private val profileJson = """{"id":"u1","email":"anna@example.com","display_name":"Анна","role":"user","base_currency":"RUB"}"""
    private val tokensJson = """{"access_token":"new-access","refresh_token":"new-refresh","token_type":"bearer","expires_in":900,"role":"analyst","user_id":"u1"}"""

    @Before
    fun setUp() {
        server.start()
        session.save("old-access", "old-refresh", Session("u1", "anna@example.com", "user"))
        network = NetworkModule(baseUrl = { server.url("/").toString() }, session = session, onSessionExpired = { sessionExpired = true })
    }

    @After
    fun tearDown() = server.shutdown()

    /** Запрос направляется на адрес сервера из настроек с токеном доступа. */
    @Test
    fun requestGoesToConfiguredServerWithAccessToken() = runBlocking {
        server.enqueue(MockResponse().setBody(profileJson))
        val profile = network.api.profile()
        val request = server.takeRequest()
        assertEquals("/api/v1/profile", request.path)
        assertEquals("Bearer old-access", request.getHeader("Authorization"))
        assertEquals("Анна", profile.displayName)
        assertEquals("RUB", profile.baseCurrency)
    }

    /** При ответе 401 токен обновляется и запрос повторяется. */
    @Test
    fun tokenIsRefreshedAndRequestRetriedOn401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Токен недействителен"}"""))
        server.enqueue(MockResponse().setBody(tokensJson))
        server.enqueue(MockResponse().setBody(profileJson))

        val result = apiCall { network.api.profile() }

        assertTrue(result.isSuccess)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("old-refresh"))
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("new-access", session.accessToken)
        assertEquals("new-refresh", session.refreshToken)
        assertEquals("analyst", session.session.value?.role)
        assertFalse(sessionExpired)
    }

    /** Если токен обновления отклонен сеанс завершается. */
    @Test
    fun sessionExpiresWhenRefreshTokenIsRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Сеанс завершен. Выполните вход повторно"}"""))

        val error = ApiError.from(apiCall { network.api.profile() }.exceptionOrNull()!!)

        assertEquals(401, error.code)
        assertTrue(sessionExpired)
        assertEquals(2, server.requestCount)
    }

    /** Запрос кода двухфакторной аутентификации распознается по заголовку. */
    @Test
    fun twoFactorRequestIsDetectedByHeader() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("X-2FA-Required", "true")
                .setBody("""{"detail":"Требуется код двухфакторной аутентификации"}""")
        )
        val error = ApiError.from(apiCall { network.api.login(LoginRequest("anna@example.com", "password1")) }.exceptionOrNull()!!)
        assertTrue(error.twoFactorRequired)
        assertEquals("Требуется код двухфакторной аутентификации", error.message)
        // Для запроса входа обновление токена не выполняется
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    /** Блокировка входа и режим обслуживания сообщаются текстом сервера. */
    @Test
    fun lockoutAndMaintenanceUseServerMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(423).setBody("""{"detail":"Вход временно заблокирован из-за неудачных попыток. Повторите через 15 мин."}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"Выполняются технические работы"}"""))

        val locked = ApiError.from(apiCall { network.api.login(LoginRequest("a@b.ru", "x")) }.exceptionOrNull()!!)
        assertEquals(423, locked.code)
        assertTrue(locked.message.startsWith("Вход временно заблокирован"))

        val maintenance = ApiError.from(apiCall { network.api.profile() }.exceptionOrNull()!!)
        assertTrue(maintenance.isMaintenance)
        assertEquals("Выполняются технические работы", maintenance.message)
    }

    /** Недоступность сервера сообщается как отсутствие соединения. */
    @Test
    fun unreachableServerIsReportedAsOffline() = runBlocking {
        server.shutdown()
        val error = ApiError.from(apiCall { network.api.profile() }.exceptionOrNull()!!)
        assertTrue(error.isOffline)
        assertEquals(ApiError.OFFLINE_MESSAGE, error.message)
        assertFalse(sessionExpired)
    }
}
