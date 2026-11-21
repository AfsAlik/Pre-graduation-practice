"""Подсистема бюджетирования и целей (п. 4.2.1.2 ТЗ, п. 4.1.6 ПМИ)."""
from datetime import date, timedelta

from app.services import notifier
from app.services.periods import add_months


def test_budget_scenario_from_test_program(api):
    """Лимит 10 000 руб. на «Кафе»; расходы 8 000 руб. -> уведомление, еще 3 000 руб. -> превышение."""
    account = api.account(initial="100000")
    cafe = api.category_id("Кафе")
    budget = api.post("/budgets", {"category_id": cafe, "period": "month", "limit_amount": "10000"})
    for name, period in (("Продукты", "week"), ("Транспорт", "month"), ("Развлечения", "year"),
                         ("Одежда", "month")):
        api.post("/budgets", {"category_id": api.category_id(name), "period": period,
                              "limit_amount": "5000"})
    assert len(api.get("/budgets")) == 5

    api.spend(account["id"], 5000, "Кафе")
    state = api.get(f"/budgets/{budget['id']}/status")
    assert state["status"] == "ok" and state["warning"] is False and state["percent"] == 50.0

    api.spend(account["id"], 3000, "Кафе")  # всего 8 000 = 80 %
    state = api.get(f"/budgets/{budget['id']}/status")
    assert state["status"] == "ok" and state["status_title"] == "в пределах лимита"
    assert state["warning"] is True and state["remaining"] == "2000.00"
    kinds = [n["kind"] for n in api.get("/notifications")["items"]]
    assert kinds.count("budget_warning") == 1
    assert any(m["channel"] == "push" and "почти исчерпан" in m["title"] for m in notifier.outbox)

    api.spend(account["id"], 3000, "Кафе")  # всего 11 000 = 110 %
    state = api.get(f"/budgets/{budget['id']}/status")
    assert state["status"] == "exceeded" and state["status_title"] == "превышение"
    assert state["percent"] == 110.0 and state["remaining"] == "-1000.00"
    kinds = [n["kind"] for n in api.get("/notifications")["items"]]
    assert kinds.count("budget_exceeded") == 1

    api.spend(account["id"], 2000, "Кафе")  # всего 13 000 = 130 %
    state = api.get(f"/budgets/{budget['id']}/status")
    assert state["status"] == "critical" and state["status_title"] == "критическое превышение"
    # Уведомление о превышении за период формируется один раз
    kinds = [n["kind"] for n in api.get("/notifications")["items"]]
    assert kinds.count("budget_exceeded") == 1 and kinds.count("budget_warning") == 1


def test_duplicate_budget_is_rejected_and_only_expense_categories(api):
    cafe = api.category_id("Кафе")
    api.post("/budgets", {"category_id": cafe, "period": "month", "limit_amount": "1000"})
    api.post("/budgets", {"category_id": cafe, "period": "month", "limit_amount": "2000"}, expected=409)
    api.post("/budgets", {"category_id": cafe, "period": "week", "limit_amount": "500"})
    api.post("/budgets", {"category_id": api.category_id("Зарплата"), "period": "month",
                          "limit_amount": "1000"}, expected=422)


def test_custom_warning_threshold(api):
    account = api.account(initial="10000")
    api.patch("/profile", {"notification_settings": {"budget_threshold_percent": 50}})
    api.post("/budgets", {"category_id": api.category_id("Кафе"), "period": "month",
                          "limit_amount": "1000"})
    api.spend(account["id"], 550, "Кафе")
    assert api.get("/budgets/status")[0]["warning"] is True
    assert [n["kind"] for n in api.get("/notifications")["items"]] == ["budget_warning"]


def test_goal_scenario_from_test_program(api):
    """Цель «Отпуск» 100 000 руб. на 6 месяцев; накопление 40 000 руб. -> прогресс 40 %."""
    deadline = add_months(date.today(), 6)
    goal = api.post("/goals", {"name": "Отпуск", "target_amount": "100000",
                               "deadline": deadline.isoformat(), "priority": "high"})
    api.post("/goal-contributions", {"goal_id": goal["id"], "amount": "40000"})
    progress = api.get(f"/goals/{goal['id']}/progress")
    assert progress["percent"] == 40.0
    assert progress["saved"] == "40000.00" and progress["remaining"] == "60000.00"
    # Рекомендуемая сумма: остаток 60 000 на 6 месяцев
    assert progress["recommended_monthly"] == "10000.00"
    # Прогноз: 40 000 за расчетный месяц -> еще 60 000 за 45 дней
    assert progress["forecast_date"] == (date.today() + timedelta(days=45)).isoformat()
    assert progress["on_track"] is True and progress["read_only"] is False

    api.post("/goal-contributions", {"goal_id": goal["id"], "amount": "60000"})
    progress = api.get(f"/goals/{goal['id']}/progress")
    assert progress["percent"] == 100.0 and progress["read_only"] is True
    assert progress["goal"]["status"] == "achieved"
    assert any(n["kind"] == "goal_achieved" for n in api.get("/notifications")["items"])
    # Достигнутая цель доступна только для просмотра
    api.put(f"/goals/{goal['id']}", {"name": "Отпуск 2", "target_amount": "200000",
                                     "deadline": deadline.isoformat()}, expected=409)
    api.post("/goal-contributions", {"goal_id": goal["id"], "amount": "1"}, expected=409)
    api.delete(f"/goals/{goal['id']}", expected=409)
    assert api.get("/dashboard")["goal"] is None  # активных целей нет


def test_goal_behind_schedule(api):
    deadline = add_months(date.today(), 2)
    goal = api.post("/goals", {"name": "Ноутбук", "target_amount": "120000",
                               "deadline": deadline.isoformat()})
    api.post("/goal-contributions", {"goal_id": goal["id"], "amount": "6000"})
    progress = api.get(f"/goals/{goal['id']}/progress")
    assert progress["percent"] == 5.0 and progress["on_track"] is False
    assert progress["recommended_monthly"] == "57000.00"
    assert api.get("/dashboard")["goal"]["goal"]["name"] == "Ноутбук"


def test_goal_deadline_must_be_in_future(api):
    api.post("/goals", {"name": "Вчера", "target_amount": "1000",
                        "deadline": (date.today() - timedelta(days=1)).isoformat()}, expected=422)


def test_lost_delivery_task_is_redelivered(api, db):
    """Уведомление, задача доставки которого потеряна, передается повторно периодической задачей."""
    from datetime import timedelta

    from app.models import Notification, utcnow

    account = api.account(initial="100000")
    budget_category = api.category_id("Кафе")
    api.post("/budgets", {"category_id": budget_category, "period": "month", "limit_amount": "1000"})
    api.spend(account["id"], 900, "Кафе")
    notification = db.query(Notification).filter(Notification.kind == "budget_warning").order_by(
        Notification.created_at.desc()).first()
    # Имитация аварийного завершения процесса: уведомление сохранено, но не передано
    notification.push_sent_at = None
    notification.event_at = utcnow() - timedelta(minutes=5)
    db.commit()
    notifier.outbox.clear()

    assert notifier.redeliver_pending(db) >= 1
    db.commit()
    db.refresh(notification)
    assert notification.push_sent_at is not None
    assert any(m["channel"] == "push" for m in notifier.outbox)
    assert notifier.redeliver_pending(db) == 0  # повторно не передается
