package ru.finhelper.app.data.prefs

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.finhelper.app.core.Ids

/**
 * Несекретные настройки приложения: адрес сервера, признак прохождения онбординга,
 * номер последней полученной ревизии и т. п.
 */
class SettingsStore(private val preferences: SharedPreferences, private val defaultBaseUrl: String) {
    private val themeState = MutableStateFlow(preferences.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM)

    /** Цветовая тема: light, dark или system (п. 4.4.5.1 ТЗ). */
    val theme: StateFlow<String> = themeState

    fun setTheme(value: String) {
        preferences.edit().putString(KEY_THEME, value).apply()
        themeState.value = value
    }

    /** Адрес серверной части; изменяется на экране входа. */
    var baseUrl: String
        get() = preferences.getString(KEY_BASE_URL, null) ?: defaultBaseUrl
        set(value) = preferences.edit().putString(KEY_BASE_URL, normalizeUrl(value)).apply()

    var onboardingDone: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING, false)
        set(value) = preferences.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /** Разрешение на показ уведомлений уже запрашивалось (запрос выполняется один раз). */
    var notificationPermissionAsked: Boolean
        get() = preferences.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
        set(value) = preferences.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, value).apply()

    /** Номер последней ревизии данных, полученной от сервера. */
    var sinceRev: Long
        get() = preferences.getLong(KEY_SINCE_REV, 0)
        set(value) = preferences.edit().putLong(KEY_SINCE_REV, value).apply()

    var lastSyncAt: Long
        get() = preferences.getLong(KEY_LAST_SYNC, 0)
        set(value) = preferences.edit().putLong(KEY_LAST_SYNC, value).apply()

    /** Пользователь, данные которого находятся в локальной базе. */
    var dataOwnerId: String?
        get() = preferences.getString(KEY_OWNER, null)
        set(value) = preferences.edit().putString(KEY_OWNER, value).apply()

    /** Профиль изменен на устройстве и еще не отправлен на сервер. */
    var profileDirty: Boolean
        get() = preferences.getBoolean(KEY_PROFILE_DIRTY, false)
        set(value) = preferences.edit().putBoolean(KEY_PROFILE_DIRTY, value).apply()

    var profileJson: String?
        get() = preferences.getString(KEY_PROFILE, null)
        set(value) = preferences.edit().putString(KEY_PROFILE, value).apply()

    /** Постоянный идентификатор установки приложения. */
    val deviceId: String
        get() = preferences.getString(KEY_DEVICE, null)
            ?: Ids.random().also { preferences.edit().putString(KEY_DEVICE, it).apply() }

    /** Ключи уже показанных локальных уведомлений (чтобы не повторять их в том же периоде). */
    var shownAlerts: Set<String>
        get() = preferences.getStringSet(KEY_ALERTS, emptySet()).orEmpty()
        set(value) = preferences.edit().putStringSet(KEY_ALERTS, value).apply()

    /** Сбрасывает сведения, относящиеся к данным пользователя (при смене учетной записи). */
    fun resetUserData() {
        preferences.edit()
            .remove(KEY_SINCE_REV).remove(KEY_LAST_SYNC).remove(KEY_OWNER)
            .remove(KEY_PROFILE).remove(KEY_PROFILE_DIRTY).remove(KEY_ALERTS)
            .apply()
    }

    companion object {
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        private const val KEY_THEME = "theme"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        private const val KEY_SINCE_REV = "since_rev"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_OWNER = "data_owner_id"
        private const val KEY_PROFILE = "profile"
        private const val KEY_PROFILE_DIRTY = "profile_dirty"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_ALERTS = "shown_alerts"

        /** Приводит адрес сервера к виду «схема://узел:порт/». */
        fun normalizeUrl(raw: String): String {
            var url = raw.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            return if (url.endsWith("/")) url else "$url/"
        }
    }
}
