"""Подсистема аналитики и отчетности (п. 4.2.1.4 ТЗ, п. 4.1.8 ПМИ)."""
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

from app.services.periods import add_months, month_start, period_bounds


def _history(api, months=6):
    """Демонстрационные данные: доходы и расходы за несколько месяцев."""
    account = api.account("Карта", initial="20000")
    cash = api.account("Наличные", "cash", initial="5000")
    today = date.today()
    for index in range(months, -1, -1):
        day = datetime.combine(add_months(month_start(today), -index), datetime.min.time(),
                               tzinfo=UTC) + timedelta(hours=10)
        if day.date() > today:
            continue
        api.earn(account["id"], 80000, occurred_at=day.isoformat())
        api.spend(account["id"], 25000, "Жилье", occurred_at=day.isoformat(), merchant="Аренда квартиры")
        api.spend(account["id"], 12000 + index * 500, "Продукты", occurred_at=day.isoformat())
        api.spend(cash["id"], 4000, "Кафе", occurred_at=day.isoformat())
    return account, cash


def test_dashboard_contains_all_blocks(api):
    account, _ = _history(api, 2)
    api.post("/budgets", {"category_id": api.category_id("Продукты"), "period": "month",
                          "limit_amount": "20000"})
    goal = api.post("/goals", {"name": "Подушка", "target_amount": "300000",
                               "deadline": add_months(date.today(), 12).isoformat()})
    api.post("/goal-contributions", {"goal_id": goal["id"], "amount": "30000"})
    api.post("/investments/trades", {"asset_type": "stock", "ticker": "SBER", "quantity": "10",
                                     "price": "250"})
    dashboard = api.get("/dashboard")
    for key in ("total_balance", "income", "expense", "expense_chart", "expenses_by_category",
                "budget", "goal", "portfolio", "forecast", "rates"):
        assert key in dashboard, key
    assert Decimal(dashboard["income"]) == Decimal("80000")
    assert Decimal(dashboard["expense"]) == Decimal("41000")  # 25000 + 12000 + 4000
    assert dashboard["expenses_by_category"][0]["name"] == "Жилье"
    assert dashboard["budget"]["percent"] == 60.0
    assert dashboard["goal"]["percent"] == 10.0
    assert dashboard["portfolio"]["positions"] == 1 and Decimal(dashboard["portfolio"]["value"]) == 3000
    assert len(dashboard["expense_chart"]) == date.today().day
    forecast = dashboard["forecast"]
    assert forecast["days_left"] == period_bounds("month")[1].day - date.today().day
    expected = (Decimal(forecast["current_balance"]) + Decimal(forecast["expected_income"])
                - Decimal(forecast["expected_expense"]))
    assert abs(Decimal(forecast["forecast_balance"]) - expected) <= Decimal("0.01")


def test_report_totals_match_transactions(api):
    account, cash = _history(api, 3)
    start, end = period_bounds("month")
    report = api.get("/reports", params={"start": start.isoformat(), "end": end.isoformat()})
    items = api.get("/transactions", params={"date_from": start.isoformat(),
                                             "date_to": end.isoformat(), "limit": 500})["items"]
    income = sum(Decimal(i["amount"]) for i in items if i["kind"] == "income")
    expense = sum(Decimal(i["amount"]) for i in items if i["kind"] == "expense")
    assert Decimal(report["income"]) == income and Decimal(report["expense"]) == expense
    assert Decimal(report["balance"]) == income - expense
    assert report["operations"] == len(items)
    by_category = {row["name"]: Decimal(row["amount"]) for row in report["expenses_by_category"]}
    assert by_category == {"Жилье": Decimal("25000"), "Продукты": Decimal("12000"), "Кафе": Decimal("4000")}
    assert abs(sum(row["share"] for row in report["expenses_by_category"]) - 100.0) < 0.5
    assert "Жилье" in report["summary"] and len(report["monthly"]) == 12

    # Фильтр по счету и по категории изменяет данные отчета
    cash_only = api.get("/reports", params={"start": start.isoformat(), "end": end.isoformat(),
                                            "account_id": cash["id"]})
    assert Decimal(cash_only["expense"]) == Decimal("4000") and Decimal(cash_only["income"]) == 0
    food = api.get("/reports", params={"start": start.isoformat(), "end": end.isoformat(),
                                       "category_id": api.category_id("Продукты")})
    assert Decimal(food["expense"]) == Decimal("12000")
    api.get("/reports", params={"start": end.isoformat(), "end": start.isoformat()}, expected=422)


def test_report_in_base_currency(api):
    usd = api.account("USD", "bank", "USD", "1000")
    api.spend(usd["id"], 10, "Кафе")
    report = api.get("/reports")
    assert Decimal(report["expense"]) == Decimal("900")  # 10 USD по курсу 90
    api.patch("/profile", {"base_currency": "USD"})
    assert Decimal(api.get("/reports")["expense"]) == Decimal("10")
    api.patch("/profile", {"base_currency": "XXX"}, expected=422)
