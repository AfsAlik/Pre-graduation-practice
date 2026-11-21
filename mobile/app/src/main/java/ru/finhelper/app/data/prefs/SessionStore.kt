package ru.finhelper.app.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Сведения о текущем сеансе пользователя. */
data class Session(val userId: String, val email: String, val role: String) {
    /** Доступ к разделам аналитика (роль «Аналитик» или «Администратор», п. 4.1.1.10 ТЗ). */
    val isAnalyst: Boolean get() = role == ROLE_ANALYST || role == ROLE_ADMIN
    val isAdmin: Boolean get() = role == ROLE_ADMIN

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ANALYST = "analyst"
        const val ROLE_ADMIN = "admin"
    }
}

/** Хранение токенов доступа и сведений о сеансе в защищенном хранилище. */
class SessionStore(private val store: SecureStore) {
    private val state = MutableStateFlow(load())

    /** Текущий сеанс; null – вход не выполнен. */
    val session: StateFlow<Session?> = state

    val accessToken: String? get() = store.get(KEY_ACCESS)
    val refreshToken: String? get() = store.get(KEY_REFRESH)

    fun save(accessToken: String, refreshToken: String, session: Session) {
        store.put(KEY_ACCESS, accessToken)
        store.put(KEY_REFRESH, refreshToken)
        store.put(KEY_USER, session.userId)
        store.put(KEY_EMAIL, session.email)
        store.put(KEY_ROLE, session.role)
        state.value = session
    }

    fun updateTokens(accessToken: String, refreshToken: String, role: String) {
        store.put(KEY_ACCESS, accessToken)
        store.put(KEY_REFRESH, refreshToken)
        store.put(KEY_ROLE, role)
        state.value = state.value?.copy(role = role)
    }

    fun clear() {
        store.remove(KEY_ACCESS, KEY_REFRESH, KEY_USER, KEY_EMAIL, KEY_ROLE)
        state.value = null
    }

    private fun load(): Session? {
        val userId = store.get(KEY_USER) ?: return null
        if (store.get(KEY_REFRESH) == null) return null
        return Session(userId, store.get(KEY_EMAIL).orEmpty(), store.get(KEY_ROLE) ?: Session.ROLE_USER)
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER = "user_id"
        const val KEY_EMAIL = "email"
        const val KEY_ROLE = "role"
    }
}
