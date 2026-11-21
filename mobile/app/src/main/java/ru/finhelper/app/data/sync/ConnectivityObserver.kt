package ru.finhelper.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Источник сведений о наличии подключения к сети. */
interface ConnectivityObserver {
    /** true – устройство подключено к сети Интернет. */
    val online: StateFlow<Boolean>
}

/**
 * Отслеживает подключение к сети средствами ConnectivityManager. Пока приложение
 * открыто, восстановление связи запускает синхронизацию (п. 4.2.2.11 ТЗ).
 */
class AndroidConnectivityObserver(context: Context) : ConnectivityObserver {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val state = MutableStateFlow(isConnected())
    override val online: StateFlow<Boolean> = state

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                state.value = true
            }

            override fun onLost(network: Network) {
                state.value = isConnected()
            }
        })
    }

    private fun isConnected(): Boolean {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
