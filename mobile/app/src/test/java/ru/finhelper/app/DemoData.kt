package ru.finhelper.app

import ru.finhelper.app.core.Dates
import ru.finhelper.app.data.local.AccountEntity
import ru.finhelper.app.data.local.BudgetEntity
import ru.finhelper.app.data.local.GoalContributionEntity
import ru.finhelper.app.data.local.GoalEntity
import ru.finhelper.app.data.local.RecurringPaymentEntity
import ru.finhelper.app.data.local.SystemCategories
import ru.finhelper.app.data.local.TemplateEntity
import ru.finhelper.app.data.local.TradeEntity
import ru.finhelper.app.data.local.TransactionEntity
import ru.finhelper.app.data.remote.NotificationSettingsDto
import ru.finhelper.app.data.remote.ProfileDto
import ru.finhelper.app.data.repo.FinanceSnapshot
import ru.finhelper.app.domain.CurrencyConverter
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Демонстрационный набор данных: используется для снимков экранов руководства
 * пользователя и в тестах расчетов. Все даты отсчитываются от [TODAY].
 */
object DemoData {
    val TODAY: LocalDate = LocalDate.of(2026, 11, 20)
    val NOW: Long get() = Dates.noon(TODAY)

    private fun category(name: String) = SystemCategories.id(name)
    private fun at(date: String): Long = Dates.noon(LocalDate.parse(date))

    const val CASH = "acc-cash"
    const val CARD = "acc-card"
    const val SAVINGS = "acc-savings"
    const val USD = "acc-usd"
    const val CREDIT = "acc-credit"

    val accounts = listOf(
        AccountEntity(CASH, "Наличные", "cash", "RUB", BigDecimal("8500.00"), createdAt = at("2026-01-10")),
        AccountEntity(CARD, "Карта Сбербанк", "bank", "RUB", BigDecimal("42300.00"), createdAt = at("2026-01-10")),
        AccountEntity(SAVINGS, "Накопительный счет", "bank", "RUB", BigDecimal("150000.00"), createdAt = at("2026-02-01")),
        AccountEntity(USD, "Долларовый счет", "bank", "USD", BigDecimal("1200.00"), createdAt = at("2026-03-05")),
        AccountEntity(CREDIT, "Кредитная карта", "credit", "RUB", BigDecimal("-18400.00"), createdAt = at("2026-04-12")),
    )

    private var sequence = 0

    private fun expense(date: String, amount: String, categoryName: String, merchant: String, account: String = CARD, auto: Boolean = false) =
        TransactionEntity(
            id = "tx-${++sequence}", kind = "expense", amount = BigDecimal(amount), accountId = account,
            categoryId = category(categoryName), occurredAt = at(date), merchant = merchant, autoCategorized = auto, createdAt = at(date),
        )

    private fun income(date: String, amount: String, categoryName: String, merchant: String) = TransactionEntity(
        id = "tx-${++sequence}", kind = "income", amount = BigDecimal(amount), accountId = CARD,
        categoryId = category(categoryName), occurredAt = at(date), merchant = merchant, createdAt = at(date),
    )

    /** Операции одного месяца по типовому сценарию; [scale] немного меняет суммы от месяца к месяцу. */
    private fun month(prefix: String, scale: Double, lastDay: Int): List<TransactionEntity> {
        fun sum(value: Int) = (value * scale).toInt().toString() + ".00"
        val rows = mutableListOf(
            expense("$prefix-01", "35000.00", "Жилье/Аренда", "Аренда квартиры"),
            income("$prefix-05", "95000.00", "Зарплата", "ООО «Вектор»"),
            expense("$prefix-02", sum(3240), "Продукты/Супермаркеты", "Пятерочка", auto = true),
            expense("$prefix-03", sum(890), "Кафе/Кофейни", "Кофейня «Зерно»", CASH),
            expense("$prefix-04", sum(650), "Транспорт/Такси", "Яндекс Такси", auto = true),
            expense("$prefix-06", sum(4860), "Продукты/Супермаркеты", "Перекресток", auto = true),
            expense("$prefix-07", sum(2350), "Кафе/Рестораны", "Ресторан «Хмели-Сунели»"),
            expense("$prefix-08", "699.00", "Развлечения/Подписки", "Яндекс Плюс"),
            expense("$prefix-09", "750.00", "Связь/Мобильная связь", "МТС"),
            expense("$prefix-10", sum(5120), "Продукты/Супермаркеты", "Лента", auto = true),
            expense("$prefix-11", sum(1480), "Кафе/Доставка еды", "Самокат"),
            expense("$prefix-12", "2400.00", "Транспорт/Проезд", "Транспортная карта"),
            expense("$prefix-13", sum(2890), "Развлечения/Кино", "Кинотеатр «Салют»"),
            expense("$prefix-14", sum(3760), "Продукты/Рынок", "Шарташский рынок", CASH),
            expense("$prefix-15", sum(1920), "Здоровье/Аптеки", "Аптека «Живика»"),
            expense("$prefix-16", sum(2740), "Кафе/Рестораны", "Суши-бар"),
            expense("$prefix-17", "6200.00", "Жилье/Коммунальные услуги", "ЕРЦ"),
            expense("$prefix-18", sum(4310), "Продукты/Супермаркеты", "Пятерочка", auto = true),
            expense("$prefix-19", sum(1750), "Развлечения/Игры", "Steam"),
            expense("$prefix-19", sum(2440), "Кафе/Рестораны", "Бар «Дом печати»"),
        )
        if (lastDay >= 20) rows += income("$prefix-20", "45000.00", "Зарплата", "ООО «Вектор» (аванс)")
        if (lastDay >= 25) {
            rows += expense("$prefix-22", sum(5600), "Одежда", "Спортмастер")
            rows += expense("$prefix-24", sum(3980), "Продукты/Супермаркеты", "Перекресток", auto = true)
            rows += expense("$prefix-26", sum(1650), "Транспорт/Топливо", "Газпромнефть")
            rows += TransactionEntity(
                id = "tx-${++sequence}", kind = "transfer", amount = BigDecimal("15000.00"), accountId = CARD,
                toAccountId = SAVINGS, amountTo = BigDecimal("15000.00"), occurredAt = at("$prefix-25"), comment = "Накопления", createdAt = at("$prefix-25"),
            )
        }
        return rows
    }

    val transactions: List<TransactionEntity> =
        (month("2026-09", 0.92, 30) + month("2026-10", 1.0, 31) + month("2026-11", 1.06, 20)).sortedByDescending { it.occurredAt }

    val budgets = listOf(
        BudgetEntity("bud-food", category("Продукты"), "month", BigDecimal("26000.00"), createdAt = at("2026-09-01")),
        BudgetEntity("bud-cafe", category("Кафе"), "month", BigDecimal("8000.00"), createdAt = at("2026-09-01")),
        BudgetEntity("bud-fun", category("Развлечения"), "month", BigDecimal("5000.00"), createdAt = at("2026-09-01")),
        BudgetEntity("bud-transport", category("Транспорт"), "month", BigDecimal("6000.00"), createdAt = at("2026-09-01")),
    )

    val goals = listOf(
        GoalEntity("goal-vacation", "Отпуск в Сочи", BigDecimal("120000.00"), "RUB", "2027-06-01", "high", createdAt = at("2026-07-01")),
        GoalEntity("goal-laptop", "Новый ноутбук", BigDecimal("90000.00"), "RUB", "2027-03-01", "medium", createdAt = at("2026-05-15")),
        GoalEntity("goal-reserve", "Резервный фонд", BigDecimal("300000.00"), "RUB", "2027-08-31", "low", createdAt = at("2026-08-01")),
        GoalEntity(
            "goal-bike", "Велосипед", BigDecimal("35000.00"), "RUB", "2026-10-01", "medium",
            status = "achieved", achievedAt = at("2026-09-12"), createdAt = at("2026-03-01"),
        ),
    )

    private fun contribution(id: String, goal: String, date: String, amount: String) =
        GoalContributionEntity(id, goal, BigDecimal(amount), at(date), createdAt = at(date))

    val contributions = listOf(
        contribution("c1", "goal-vacation", "2026-07-25", "12000.00"),
        contribution("c2", "goal-vacation", "2026-08-25", "12000.00"),
        contribution("c3", "goal-vacation", "2026-09-25", "12000.00"),
        contribution("c4", "goal-vacation", "2026-10-25", "12000.00"),
        contribution("c5", "goal-laptop", "2026-05-25", "20000.00"),
        contribution("c6", "goal-laptop", "2026-07-25", "25000.00"),
        contribution("c7", "goal-laptop", "2026-09-25", "16500.00"),
        contribution("c8", "goal-laptop", "2026-10-25", "15000.00"),
        contribution("c9", "goal-reserve", "2026-08-25", "20000.00"),
        contribution("c10", "goal-reserve", "2026-10-25", "10000.00"),
        contribution("c11", "goal-bike", "2026-06-10", "20000.00"),
        contribution("c12", "goal-bike", "2026-09-12", "15000.00"),
    )

    private fun trade(id: String, type: String, ticker: String, name: String, date: String, quantity: String, price: String, fee: String, side: String = "buy") =
        TradeEntity(id, type, ticker, name, side, BigDecimal(quantity), BigDecimal(price), BigDecimal(fee), "RUB", at(date), createdAt = at(date))

    val trades = listOf(
        trade("tr1", "stock", "SBER", "Сбербанк", "2026-06-10", "100", "265.40", "13.27"),
        trade("tr2", "stock", "SBER", "Сбербанк", "2026-11-17", "50", "281.10", "7.03"),
        trade("tr3", "stock", "GAZP", "Газпром", "2026-07-02", "200", "128.50", "12.85"),
        trade("tr4", "bond", "SU26238RMFS4", "ОФЗ 26238", "2026-08-14", "30", "612.30", "9.18"),
        trade("tr5", "etf", "TMOS", "Т-Капитал Индекс МосБиржи", "2026-05-20", "1500", "6.42", "4.82"),
        trade("tr6", "crypto", "BTC", "Биткоин", "2026-09-03", "0.015", "6200000", "465.00"),
    )

    val prices = mapOf(
        "SBER" to BigDecimal("302.50"), "GAZP" to BigDecimal("121.30"), "SU26238RMFS4" to BigDecimal("628.90"),
        "TMOS" to BigDecimal("6.98"), "BTC" to BigDecimal("6850000"),
    )

    val templates = listOf(
        TemplateEntity("tpl1", "Обед в столовой", "expense", BigDecimal("380.00"), "RUB", CARD, category("Кафе")),
        TemplateEntity("tpl2", "Проезд на метро", "expense", BigDecimal("42.00"), "RUB", CARD, category("Транспорт/Проезд")),
        TemplateEntity("tpl3", "Кофе", "expense", BigDecimal("240.00"), "RUB", CASH, category("Кафе/Кофейни")),
    )

    val recurring = listOf(
        RecurringPaymentEntity("rp1", "Аренда квартиры", BigDecimal("35000.00"), "RUB", CARD, category("Жилье/Аренда"), "month", "2026-12-01", 3),
        RecurringPaymentEntity("rp2", "Интернет Дом.ру", BigDecimal("650.00"), "RUB", CARD, category("Связь/Интернет"), "month", "2026-11-20", 1),
        RecurringPaymentEntity("rp3", "Яндекс Плюс", BigDecimal("699.00"), "RUB", CARD, category("Развлечения/Подписки"), "month", "2026-12-08", 1),
    )

    val converter = CurrencyConverter(
        mapOf("USD" to BigDecimal("81.25"), "EUR" to BigDecimal("94.10"), "CNY" to BigDecimal("11.42")), asOf = "2026-11-20",
    )

    val profile = ProfileDto(
        id = "user-1", email = "anna@example.com", displayName = "Анна Смирнова", role = "user", baseCurrency = "RUB",
        riskProfile = "moderate", investHorizonYears = 5, theme = "light", notificationSettings = NotificationSettingsDto(),
    )

    val snapshot = FinanceSnapshot(
        accounts = accounts, categories = SystemCategories.all(), transactions = transactions, budgets = budgets, goals = goals,
        contributions = contributions, trades = trades, prices = prices, pricesAt = Dates.noon(TODAY) - 3_600_000,
        converter = converter, profile = profile, loaded = true,
    )
}
