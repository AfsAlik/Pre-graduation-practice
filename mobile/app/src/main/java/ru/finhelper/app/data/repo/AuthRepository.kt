package ru.finhelper.app.data.repo

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.finhelper.app.data.local.AppDatabase
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.prefs.Session
import ru.finhelper.app.data.prefs.SessionStore
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.remote.ChangePasswordRequest
import ru.finhelper.app.data.remote.CodeRequest
import ru.finhelper.app.data.remote.DeviceRequest
import ru.finhelper.app.data.remote.EmailRequest
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.LoginRequest
import ru.finhelper.app.data.remote.PasswordRequest
import ru.finhelper.app.data.remote.RegisterRequest
import ru.finhelper.app.data.remote.ResetPasswordRequest
import ru.finhelper.app.data.remote.TotpSetupResponse
import ru.finhelper.app.data.remote.apiCall

/**
 * Регистрация, вход и управление учетной записью (п. 4.2.1.8 а, б ТЗ).
 *
 * @param pushToken поставщик токена доставки push-уведомлений (пустая строка, если FCM не настроен)
 */
class AuthRepository(
    private val api: FinHelperApi,
    private val session: SessionStore,
    private val settings: SettingsStore,
    private val db: AppDatabase,
    private val profiles: ProfileRepository,
    private val pushToken: suspend () -> String = { "" },
) {
    /**
     * Выполняет вход. Если на устройстве остались данные другого пользователя,
     * локальная база очищается.
     *
     * @param totpCode код двухфакторной аутентификации, если сервер его запросил
     */
    suspend fun login(email: String, password: String, totpCode: String?): Result<Session> = apiCall {
        val tokens = api.login(LoginRequest(email.trim(), password, totpCode?.takeIf { it.isNotBlank() }))
        if (settings.dataOwnerId != tokens.userId) wipeLocalData()
        settings.dataOwnerId = tokens.userId
        val newSession = Session(tokens.userId, email.trim().lowercase(), tokens.role)
        session.save(tokens.accessToken, tokens.refreshToken, newSession)
        seedSystemCategories()
        profiles.refresh()
        registerDevice()
        newSession
    }

    suspend fun register(email: String, password: String, displayName: String, consent: Boolean): Result<String> = apiCall {
        api.register(RegisterRequest(email.trim(), password, displayName.trim(), consent)).message
    }

    /** Запрашивает письмо с кодом восстановления пароля. */
    suspend fun forgotPassword(email: String): Result<String> = apiCall { api.forgotPassword(EmailRequest(email.trim())).message }

    suspend fun resetPassword(code: String, newPassword: String): Result<String> =
        apiCall { api.resetPassword(ResetPasswordRequest(code.trim(), newPassword)).message }

    suspend fun changePassword(current: String, new: String): Result<String> =
        apiCall { api.changePassword(ChangePasswordRequest(current, new)).message }

    suspend fun setupTwoFactor(): Result<TotpSetupResponse> = apiCall { api.setupTwoFactor() }

    suspend fun enableTwoFactor(code: String): Result<String> = apiCall {
        api.enableTwoFactor(CodeRequest(code.trim())).message.also { profiles.refresh() }
    }

    suspend fun disableTwoFactor(code: String): Result<String> = apiCall {
        api.disableTwoFactor(CodeRequest(code.trim())).message.also { profiles.refresh() }
    }

    /** Сообщает серверу идентификатор устройства и токен push-уведомлений. */
    suspend fun registerDevice() {
        if (session.session.value == null) return
        apiCall { api.registerDevice(DeviceRequest(settings.deviceId, pushToken(), "${Build.MANUFACTURER} ${Build.MODEL}".trim())) }
    }

    /** Выход из учетной записи: сеанс завершается, данные пользователя удаляются с устройства. */
    suspend fun logout() {
        apiCall { api.logout() }
        session.clear()
        wipeLocalData()
    }

    /** Удаление учетной записи (персональные данные удаляются сервером в течение 30 дней). */
    suspend fun deleteAccount(password: String): Result<String> = apiCall {
        api.deleteProfile(PasswordRequest(password)).message.also {
            session.clear()
            wipeLocalData()
        }
    }

    /** Сеанс отклонен сервером: требуется повторный вход, данные на устройстве сохраняются. */
    fun expireSession() = session.clear()

    /** Добавляет системные категории, чтобы справочник был доступен до первой синхронизации. */
    suspend fun seedSystemCategories() {
        if (db.categories().count() == 0) db.categories().upsertAll(SystemCategories.all())
    }

    private suspend fun wipeLocalData() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        settings.resetUserData()
        profiles.clear()
    }
}
