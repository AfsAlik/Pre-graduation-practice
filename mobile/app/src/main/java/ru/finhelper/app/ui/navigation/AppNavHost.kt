package ru.finhelper.app.ui.navigation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.finhelper.app.di.AppContainer
import ru.finhelper.app.ui.admin.AdminMenuScreen
import ru.finhelper.app.ui.admin.AdminViewModel
import ru.finhelper.app.ui.admin.AiAnalyticsScreen
import ru.finhelper.app.ui.admin.LogsScreen
import ru.finhelper.app.ui.admin.ModelsScreen
import ru.finhelper.app.ui.admin.MonitoringScreen
import ru.finhelper.app.ui.admin.SystemSettingsScreen
import ru.finhelper.app.ui.admin.UsersScreen
import ru.finhelper.app.ui.auth.AuthViewModel
import ru.finhelper.app.ui.auth.LoginRoute
import ru.finhelper.app.ui.auth.OnboardingScreen
import ru.finhelper.app.ui.auth.RecoveryRoute
import ru.finhelper.app.ui.auth.RegisterRoute
import ru.finhelper.app.ui.budget.BudgetRoute
import ru.finhelper.app.ui.budget.BudgetViewModel
import ru.finhelper.app.ui.components.LocalTabNavigator
import ru.finhelper.app.ui.components.MainTab
import ru.finhelper.app.ui.components.appViewModel
import ru.finhelper.app.ui.dashboard.DashboardRoute
import ru.finhelper.app.ui.dashboard.DashboardViewModel
import ru.finhelper.app.ui.goals.GoalsRoute
import ru.finhelper.app.ui.goals.GoalsViewModel
import ru.finhelper.app.ui.investments.AssetRoute
import ru.finhelper.app.ui.investments.AssetViewModel
import ru.finhelper.app.ui.investments.InvestmentsRoute
import ru.finhelper.app.ui.investments.InvestmentsViewModel
import ru.finhelper.app.ui.profile.HelpScreen
import ru.finhelper.app.ui.profile.NotificationSettingsScreen
import ru.finhelper.app.ui.profile.NotificationsScreen
import ru.finhelper.app.ui.profile.ProfileScreen
import ru.finhelper.app.ui.profile.SecurityScreen
import ru.finhelper.app.ui.profile.SettingsViewModel
import ru.finhelper.app.ui.recommendations.RecommendationsRoute
import ru.finhelper.app.ui.recommendations.RecommendationsViewModel
import ru.finhelper.app.ui.reports.ReportsRoute
import ru.finhelper.app.ui.reports.ReportsViewModel
import ru.finhelper.app.ui.transactions.AccountsScreen
import ru.finhelper.app.ui.transactions.CategoriesScreen
import ru.finhelper.app.ui.transactions.RecurringScreen
import ru.finhelper.app.ui.transactions.ReferenceViewModel
import ru.finhelper.app.ui.transactions.TemplatesScreen
import ru.finhelper.app.ui.transactions.TransactionEditRoute
import ru.finhelper.app.ui.transactions.TransactionEditViewModel
import ru.finhelper.app.ui.transactions.TransactionsRoute
import ru.finhelper.app.ui.transactions.TransactionsViewModel
import ru.finhelper.app.ui.transactions.TrashScreen

/** Экраны, доступные без входа в учетную запись. */
private val PUBLIC_ROUTES = setOf(Routes.ONBOARDING, Routes.LOGIN, Routes.REGISTER, Routes.RECOVERY)

/** Переход к разделу нижней панели: состояние разделов сохраняется, экраны не дублируются. */
private fun NavHostController.openTab(route: String) = navigate(route) {
    popUpTo(Routes.DASHBOARD) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Переход на экран входа с очисткой истории переходов. */
private fun NavHostController.openLogin() = navigate(Routes.LOGIN) {
    popUpTo(0) { inclusive = true }
    launchSingleTop = true
}

/**
 * Граф навигации приложения (одна активность, Navigation Compose).
 *
 * @param pendingRoute экран, который нужно открыть по нажатию на уведомление
 * @param onRouteHandled вызывается после перехода по [pendingRoute]
 */
@Composable
fun AppNavHost(container: AppContainer, pendingRoute: String? = null, onRouteHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    val session by container.session.session.collectAsStateWithLifecycle()
    // Модели, общие для нескольких экранов, принадлежат активности и создаются при первом обращении
    val activityOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val startRoute = remember {
        when {
            !container.settings.onboardingDone -> Routes.ONBOARDING
            container.session.session.value == null -> Routes.LOGIN
            else -> Routes.DASHBOARD
        }
    }
    val tabRoutes = remember { MainTab.entries.map { it.route }.toSet() }
    val navigate: (String) -> Unit = { route -> if (route in tabRoutes) navController.openTab(route) else navController.navigate(route) }
    val back: () -> Unit = { navController.popBackStack() }

    // Сеанс завершен (выход или отказ сервера) – возврат на экран входа
    LaunchedEffect(session) {
        val current = navController.currentDestination?.route
        if (session == null && current != null && current !in PUBLIC_ROUTES) navController.openLogin()
    }
    LaunchedEffect(pendingRoute, session) {
        if (pendingRoute != null && session != null) {
            navigate(pendingRoute)
            onRouteHandled()
        }
    }
    NotificationPermissionRequest(container, loggedIn = session != null)

    CompositionLocalProvider(LocalTabNavigator provides { tab -> navController.openTab(tab.route) }) {
        NavHost(navController = navController, startDestination = startRoute) {
            // ------------------------------------------------------------ вход
            composable(Routes.ONBOARDING) {
                val viewModel = authViewModel(activityOwner)
                OnboardingScreen(onFinish = {
                    viewModel.completeOnboarding()
                    navController.navigate(if (session == null) Routes.LOGIN else Routes.DASHBOARD) { popUpTo(0) { inclusive = true } }
                })
            }
            composable(Routes.LOGIN) {
                LoginRoute(
                    viewModel = authViewModel(activityOwner),
                    onLoggedIn = { navController.navigate(Routes.DASHBOARD) { popUpTo(0) { inclusive = true } } },
                    onRegister = { navController.navigate(Routes.REGISTER) },
                    onForgot = { navController.navigate(Routes.RECOVERY) },
                )
            }
            composable(Routes.REGISTER) { RegisterRoute(authViewModel(activityOwner), back) }
            composable(Routes.RECOVERY) { RecoveryRoute(authViewModel(activityOwner), back) }

            // ------------------------------------------------------------ разделы нижней панели
            composable(Routes.DASHBOARD) {
                val viewModel = appViewModel { DashboardViewModel(it.finance, it.session, it.database.notifications()) }
                DashboardRoute(viewModel, navigate)
            }
            composable(Routes.TRANSACTIONS) {
                val viewModel = appViewModel { TransactionsViewModel(it.finance, it.files, it.syncManager, it.messages) }
                TransactionsRoute(viewModel, onOpen = { id -> navController.navigate(Routes.transactionEdit(id)) }, onTrash = { navigate(Routes.TRASH) })
            }
            composable(Routes.BUDGET) { BudgetRoute(appViewModel { BudgetViewModel(it.finance, it.messages) }) }
            composable(Routes.GOALS) { GoalsRoute(appViewModel { GoalsViewModel(it.finance, it.messages) }) }
            composable(Routes.INVESTMENTS) {
                InvestmentsRoute(investmentsViewModel(activityOwner), onOpenAsset = { ticker -> navController.navigate(Routes.asset(ticker)) })
            }

            // ------------------------------------------------------------ транзакции и справочники
            composable(
                Routes.TRANSACTION_EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                val viewModel = appViewModel {
                    TransactionEditViewModel(id, it.finance, it.api, it.receiptRecognizer, it.messages, it.errorLogger)
                }
                TransactionEditRoute(viewModel, back)
            }
            composable(Routes.TRASH) {
                val viewModel = referenceViewModel(activityOwner)
                val rows by viewModel.trash.collectAsStateWithLifecycle()
                TrashScreen(rows, back, viewModel::restore)
            }
            composable(Routes.ACCOUNTS) {
                val viewModel = referenceViewModel(activityOwner)
                val state by viewModel.accounts.collectAsStateWithLifecycle()
                AccountsScreen(state, back, viewModel::saveAccount, viewModel::deleteAccount)
            }
            composable(Routes.CATEGORIES) {
                val viewModel = referenceViewModel(activityOwner)
                val state by viewModel.categories.collectAsStateWithLifecycle()
                CategoriesScreen(state, back, viewModel::saveCategory, viewModel::deleteCategory)
            }
            composable(Routes.TEMPLATES) {
                val viewModel = referenceViewModel(activityOwner)
                val state by viewModel.templates.collectAsStateWithLifecycle()
                TemplatesScreen(state, back, viewModel::saveTemplate, viewModel::deleteTemplate)
            }
            composable(Routes.RECURRING) {
                val viewModel = referenceViewModel(activityOwner)
                val state by viewModel.templates.collectAsStateWithLifecycle()
                RecurringScreen(state, back, viewModel::savePayment, viewModel::deletePayment, viewModel::markPaid)
            }

            // ------------------------------------------------------------ инвестиции, отчеты, рекомендации
            composable(Routes.ASSET, arguments = listOf(navArgument("ticker") { type = NavType.StringType })) { entry ->
                val ticker = entry.arguments?.getString("ticker").orEmpty()
                val viewModel = appViewModel { AssetViewModel(ticker, it.finance, it.investments) }
                AssetRoute(viewModel, investmentsViewModel(activityOwner), back)
            }
            composable(Routes.REPORTS) { ReportsRoute(appViewModel { ReportsViewModel(it.finance, it.files, it.messages) }, back) }
            composable(Routes.RECOMMENDATIONS) {
                RecommendationsRoute(appViewModel { RecommendationsViewModel(it.insights, it.syncManager, it.messages) }, back)
            }

            // ------------------------------------------------------------ профиль
            composable(Routes.PROFILE) {
                val viewModel = settingsViewModel(activityOwner)
                val state by viewModel.profile.collectAsStateWithLifecycle()
                ProfileScreen(state, back, viewModel::updateProfile, navigate, onLogout = { viewModel.logout { navController.openLogin() } })
            }
            composable(Routes.NOTIFICATION_SETTINGS) {
                val viewModel = settingsViewModel(activityOwner)
                val state by viewModel.profile.collectAsStateWithLifecycle()
                NotificationSettingsScreen(state.profile.notificationSettings, back, viewModel::updateNotifications)
            }
            composable(Routes.SECURITY) {
                val viewModel = settingsViewModel(activityOwner)
                val state by viewModel.security.collectAsStateWithLifecycle()
                SecurityScreen(
                    state, back, viewModel::startTwoFactorSetup, viewModel::enableTwoFactor, viewModel::disableTwoFactor,
                    viewModel::changePassword, onDeleteAccount = { password -> viewModel.deleteAccount(password) { navController.openLogin() } },
                )
            }
            composable(Routes.HELP) {
                val viewModel = settingsViewModel(activityOwner)
                val state by viewModel.help.collectAsStateWithLifecycle()
                HelpScreen(state, back, viewModel::loadFaq, viewModel::sendSupportRequest)
            }
            composable(Routes.NOTIFICATIONS) {
                val viewModel = settingsViewModel(activityOwner)
                val items by viewModel.notifications.collectAsStateWithLifecycle()
                NotificationsScreen(items, back, viewModel::markNotificationsRead)
            }

            // ------------------------------------------------------------ администрирование
            composable(Routes.ADMIN) { AdminMenuScreen(adminViewModel(activityOwner).isAdmin, back, navigate) }
            composable(Routes.ADMIN_AI) {
                val viewModel = adminViewModel(activityOwner)
                val state by viewModel.quality.collectAsStateWithLifecycle()
                AiAnalyticsScreen(state, back, viewModel::loadQuality)
            }
            composable(Routes.ADMIN_MODELS) {
                val viewModel = adminViewModel(activityOwner)
                val state by viewModel.models.collectAsStateWithLifecycle()
                ModelsScreen(state, back, viewModel::loadModels, viewModel::startTraining, viewModel::activate)
            }
            composable(Routes.ADMIN_USERS) {
                val viewModel = adminViewModel(activityOwner)
                val state by viewModel.users.collectAsStateWithLifecycle()
                UsersScreen(
                    state, back, viewModel::loadUsers, viewModel::createUser, viewModel::setBlocked,
                    viewModel::resetPassword, viewModel::dismissTemporaryPassword,
                )
            }
            composable(Routes.ADMIN_LOGS) {
                val viewModel = adminViewModel(activityOwner)
                val state by viewModel.logs.collectAsStateWithLifecycle()
                val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                    uri?.let(viewModel::exportLogs)
                }
                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(viewModel::importLogs)
                }
                LogsScreen(
                    state, back, viewModel::loadLogs, viewModel::updateLogFilter,
                    onExport = { exportLauncher.launch("audit_log.csv") },
                    onImport = { importLauncher.launch(arrayOf("text/*", "*/*")) },
                )
            }
            composable(Routes.ADMIN_MONITORING) {
                val viewModel = adminViewModel(activityOwner)
                val state by viewModel.monitoring.collectAsStateWithLifecycle()
                MonitoringScreen(state, back, viewModel::loadMonitoring)
            }
            composable(Routes.ADMIN_SETTINGS) {
                val viewModel = adminViewModel(activityOwner)
                val state by viewModel.settings.collectAsStateWithLifecycle()
                SystemSettingsScreen(state, back, viewModel::loadSettings, viewModel::saveSetting)
            }
        }
    }
}

@Composable
private fun authViewModel(owner: ViewModelStoreOwner): AuthViewModel =
    appViewModel(owner) { AuthViewModel(it.auth, it.settings, it.syncManager, it.messages) }

@Composable
private fun referenceViewModel(owner: ViewModelStoreOwner): ReferenceViewModel =
    appViewModel(owner) { ReferenceViewModel(it.finance, it.messages) }

@Composable
private fun investmentsViewModel(owner: ViewModelStoreOwner): InvestmentsViewModel =
    appViewModel(owner) { InvestmentsViewModel(it.finance, it.investments, it.messages) }

@Composable
private fun settingsViewModel(owner: ViewModelStoreOwner): SettingsViewModel = appViewModel(owner) {
    SettingsViewModel(
        it.profiles, it.auth, it.reference, it.api, it.finance, it.session, it.syncManager, it.database.notifications(), it.messages,
    )
}

@Composable
private fun adminViewModel(owner: ViewModelStoreOwner): AdminViewModel =
    appViewModel(owner) { AdminViewModel(it.api, it.files, it.session, it.messages) }

/**
 * Запрос разрешения на показ уведомлений (Android 13 и выше). Выполняется один
 * раз после входа; при отказе приложение продолжает работать без уведомлений.
 */
@Composable
private fun NotificationPermissionRequest(container: AppContainer, loggedIn: Boolean) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(loggedIn) {
        if (loggedIn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !container.settings.notificationPermissionAsked) {
            container.settings.notificationPermissionAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
