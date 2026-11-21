package ru.finhelper.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import ru.finhelper.app.di.AppContainer
import ru.finhelper.app.notify.Notifier
import ru.finhelper.app.ui.components.LocalAppContainer
import ru.finhelper.app.ui.components.LocalSnackbarHost
import ru.finhelper.app.ui.components.LocalSyncAction
import ru.finhelper.app.ui.components.LocalSyncStatus
import ru.finhelper.app.ui.navigation.AppNavHost
import ru.finhelper.app.ui.theme.FinHelperTheme

/** Единственная активность приложения: содержит все экраны, построенные на Jetpack Compose. */
class MainActivity : ComponentActivity() {
    /** Экран, который нужно открыть по нажатию на уведомление. */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute = intent?.getStringExtra(Notifier.EXTRA_ROUTE)
        val container = (application as FinHelperApp).container
        setContent {
            FinHelperRoot(container, pendingRoute) { pendingRoute = null }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute = intent.getStringExtra(Notifier.EXTRA_ROUTE)
    }

    override fun onResume() {
        super.onResume()
        // При возврате в приложение данные синхронизируются с сервером
        (application as FinHelperApp).container.syncManager.requestSync()
    }
}

/**
 * Корень интерфейса: тема, индикатор синхронизации, область сообщений и граф
 * навигации. Сообщения о результате действий показываются поверх любого экрана.
 */
@Composable
fun FinHelperRoot(container: AppContainer, pendingRoute: String?, onRouteHandled: () -> Unit) {
    val theme by container.settings.theme.collectAsStateWithLifecycle()
    val syncStatus by container.syncManager.status.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // Новое сообщение заменяет показанное, чтобы подтверждение не запаздывало
        container.messages.flow.collectLatest { message ->
            val text = message.text ?: context.getString(message.resource, *message.arguments.toTypedArray())
            snackbarHost.showSnackbar(text)
        }
    }
    FinHelperTheme(theme) {
        CompositionLocalProvider(
            LocalAppContainer provides container,
            LocalSyncStatus provides syncStatus,
            LocalSyncAction provides { container.syncManager.requestSync() },
            LocalSnackbarHost provides snackbarHost,
        ) {
            AppNavHost(container, pendingRoute, onRouteHandled)
        }
    }
}
