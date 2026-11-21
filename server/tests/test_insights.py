"""Подсистема интеллектуальных рекомендаций (п. 4.2.1.5 ТЗ, п. 4.1.9 ПМИ)."""
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

from app.services.periods import add_months, month_start


def _six_months(api, *, salary=80000, credit=0, reserve="300000"):
    """Учетная запись с историей операций за шесть полных месяцев."""
    account = api.account("Карта", initial=reserve)
    today = date.today()
    for index in range(6, -1, -1):
        base = datetime.combine(add_months(month_start(today), -index), datetime.min.time(),
                                tzinfo=UTC)
        if (base + timedelta(days=1)).date() > today:
            continue
        api.earn(account["id"], salary, occurred_at=(base + timedelta(hours=9)).isoformat())
        day = (base + timedelta(hours=12)).isoformat()
        api.spend(account["id"], 14000 + index * 300, "Продукты", occurred_at=day, merchant="Пятерочка")
        api.spend(account["id"], 9000, "Кафе", occurred_at=day, merchant="Шоколадница")
        api.spend(account["id"], 399, "Развлечения", occurred_at=day, merchant="Кинопоиск")
        api.spend(account["id"], 299, "Развлечения", occurred_at=day, merchant="Яндекс Плюс")
        if credit:
            api.spend(account["id"], credit, "Кредиты", occurred_at=day, merchant="Платеж по кредиту")
    return account


def test_recommendations_feedback_and_apply(api):
    _six_months(api)
    items = api.get("/recommendations")["items"]
    assert items, "рекомендации не сформированы"
    for item in items:
        assert item["title"] and item["text"] and item["explanation"]
        assert Decimal(item["estimated_saving"]) >= 0
    kinds = {i["kind"] for i in items}
    assert "set_budget" in kinds and "subscriptions" in kinds
    # Повторный запрос не создает дубликатов
    assert len(api.get("/recommendations")["items"]) == len(items)

    budget_rec = next(i for i in items if i["kind"] == "set_budget")
    assert budget_rec["action"]["label"] == "Установить бюджет"
    assert Decimal(budget_rec["estimated_saving"]) > 0
    other = next(i for i in items if i["id"] != budget_rec["id"])
    rated = api.post(f"/recommendations/{other['id']}/feedback", {"useful": True})
    assert rated["feedback"] == "useful"

    applied = api.post(f"/recommendations/{budget_rec['id']}/apply")
    assert applied["status"] == "applied" and applied["applied_at"]
    budgets = api.get("/budgets")
    assert len(budgets) == 1 and budgets[0]["category_id"] == budget_rec["action"]["category_id"]
    assert budgets[0]["limit_amount"] == budget_rec["action"]["limit_amount"]
    api.post(f"/recommendations/{budget_rec['id']}/apply", expected=409)
    # Оценка и факт применения сохраняются
    stored = {i["id"]: i for i in api.get("/recommendations", params={"refresh": False})["items"]}
    assert stored[other["id"]]["feedback"] == "useful"
    assert stored[budget_rec["id"]]["status"] == "applied"


def test_forecast_has_confidence_interval(api):
    _six_months(api)
    forecast = api.get("/forecast")
    assert forecast["month"] == add_months(month_start(date.today()), 1).strftime("%Y-%m")
    for key in ("expense", "income"):
        part = forecast[key]
        assert part["lower"] <= part["forecast"] <= part["upper"]
        assert part["history_points"] == 6
        assert part["method"] in ("exponential_smoothing", "moving_average")
    assert abs(forecast["income"]["forecast"] - 80000) < 4000
    assert 20000 < forecast["expense"]["forecast"] < 30000
    # Расходы менялись от месяца к месяцу, поэтому интервал уверенности ненулевой
    assert forecast["expense"]["upper"] > forecast["expense"]["lower"]


def test_risk_warning_for_high_debt_load_and_no_reserve(api):
    """Доля кредитных платежей более 40 % дохода и отсутствие резервного фонда."""
    _six_months(api, salary=80000, credit=36000, reserve="0")
    risks = {r["kind"]: r for r in api.get("/risks")["items"]}
    assert set(risks) == {"debt_load", "reserve_fund"}
    assert risks["debt_load"]["value"] > 0.40
    assert "кредит" in risks["debt_load"]["message"].lower()
    assert risks["reserve_fund"]["value"] < 3
    notifications = [n for n in api.get("/notifications")["items"] if n["kind"] == "risk"]
    assert len(notifications) == 2
    # Повторная оценка не дублирует предупреждения
    api.get("/risks")
    assert len([n for n in api.get("/notifications")["items"] if n["kind"] == "risk"]) == 2


def test_no_risk_for_healthy_profile(api):
    _six_months(api)
    assert api.get("/risks")["items"] == []


def test_patterns(api):
    account = _six_months(api)
    api.spend(account["id"], 95000, "Продукты", merchant="Банкет")  # аномальная операция
    patterns = api.get("/patterns")
    names = {r["name"] for r in patterns["regular"]}
    assert {"Кинопоиск", "Яндекс Плюс", "Пятерочка"} <= names
    assert any(a["amount"] == 95000 for a in patterns["anomalies"])
