package ru.finhelper.app.ui.navigation

/** Маршруты экранов приложения. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val RECOVERY = "recovery"

    const val DASHBOARD = "dashboard"
    const val TRANSACTIONS = "transactions"
    const val BUDGET = "budget"
    const val GOALS = "goals"
    const val INVESTMENTS = "investments"

    const val TRANSACTION_EDIT = "transaction_edit?id={id}"
    const val TRASH = "trash"
    const val TEMPLATES = "templates"
    const val RECURRING = "recurring"
    const val ACCOUNTS = "accounts"
    const val CATEGORIES = "categories"
    const val ASSET = "asset/{ticker}"
    const val REPORTS = "reports"
    const val RECOMMENDATIONS = "recommendations"
    const val NOTIFICATIONS = "notifications"

    const val PROFILE = "profile"
    const val NOTIFICATION_SETTINGS = "notification_settings"
    const val SECURITY = "security"
    const val HELP = "help"

    const val ADMIN = "admin"
    const val ADMIN_AI = "admin_ai"
    const val ADMIN_MODELS = "admin_models"
    const val ADMIN_USERS = "admin_users"
    const val ADMIN_LOGS = "admin_logs"
    const val ADMIN_MONITORING = "admin_monitoring"
    const val ADMIN_SETTINGS = "admin_settings"

    /** Форма транзакции: без идентификатора – добавление, с идентификатором – изменение. */
    fun transactionEdit(id: String? = null): String = if (id == null) "transaction_edit" else "transaction_edit?id=$id"

    fun asset(ticker: String): String = "asset/$ticker"
}
