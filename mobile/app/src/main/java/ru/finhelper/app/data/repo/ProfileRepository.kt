package ru.finhelper.app.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import ru.finhelper.app.data.prefs.SettingsStore
import ru.finhelper.app.data.remote.AppJson
import ru.finhelper.app.data.remote.FinHelperApi
import ru.finhelper.app.data.remote.ProfileDto
import ru.finhelper.app.data.remote.ProfileUpdateRequest
import ru.finhelper.app.data.remote.apiCall

/**
 * Профиль и настройки пользователя (п. 4.2.1.8 г ТЗ).
 *
 * Настройки сохраняются на устройстве сразу, а на сервер передаются при наличии
 * сети; неотправленные изменения досылаются после очередной синхронизации.
 */
class ProfileRepository(private val api: FinHelperApi, private val settings: SettingsStore) {
    private val state = MutableStateFlow(load())
    val profile: StateFlow<ProfileDto> = state

    private fun load(): ProfileDto = settings.profileJson
        ?.let { runCatching { AppJson.decodeFromString<ProfileDto>(it) }.getOrNull() } ?: ProfileDto()

    private fun store(profile: ProfileDto) {
        settings.profileJson = AppJson.encodeToString(profile)
        settings.setTheme(profile.theme)
        state.value = profile
    }

    /** Изменяет настройки профиля на устройстве и пытается передать их серверу. */
    suspend fun update(transform: (ProfileDto) -> ProfileDto) {
        store(transform(state.value))
        settings.profileDirty = true
        push()
    }

    /** Передает серверу измененные настройки, если они еще не отправлены. */
    suspend fun push(): Boolean {
        if (!settings.profileDirty) return true
        val current = state.value
        val request = ProfileUpdateRequest(
            displayName = current.displayName,
            baseCurrency = current.baseCurrency,
            riskProfile = current.riskProfile,
            investHorizonYears = current.investHorizonYears,
            theme = current.theme,
            costingMethod = current.costingMethod,
            onboardingDone = current.onboardingDone,
            notificationSettings = current.notificationSettings,
        )
        return apiCall { api.updateProfile(request) }.onSuccess {
            settings.profileDirty = false
            store(it)
        }.isSuccess
    }

    /** Загружает профиль с сервера; неотправленные локальные изменения имеют приоритет. */
    suspend fun refresh(): Result<ProfileDto> {
        if (settings.profileDirty && !push()) return Result.success(state.value)
        return apiCall { api.profile() }.onSuccess(::store)
    }

    /** Сбрасывает сведения о профиле при выходе из учетной записи. */
    fun clear() {
        state.value = ProfileDto(theme = settings.theme.value)
    }
}
