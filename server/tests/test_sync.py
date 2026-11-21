"""Подсистема уведомлений и синхронизации (п. 4.2.1.6 ТЗ, п. 4.1.10 ПМИ)."""
import uuid
from datetime import UTC, datetime, timedelta

from app.services import notifier


def _now(shift_seconds: int = 0) -> str:
    return (datetime.now(UTC) + timedelta(seconds=shift_seconds)).isoformat()


def _op(entity: str, data: dict, *, item_id: str | None = None, deleted=False, updated_at=None):
    return {"op_id": str(uuid.uuid4()), "entity": entity, "id": item_id or str(uuid.uuid4()),
            "updated_at": updated_at or _now(), "deleted": deleted, "data": data}


def test_first_sync_returns_system_categories(api):
    response = api.post("/sync", {"device_id": "device-0001", "since_rev": 0, "changes": []})
    names = {c["name"] for c in response["changes"]["category"]}
    assert {"Продукты", "Кафе", "Зарплата"} <= names
    assert response["rev"] == 0 and response["results"] == []


def test_offline_changes_are_applied_without_loss_or_duplication(api):
    """Три транзакции и изменение лимита, сделанные без сети, после восстановления связи."""
    account = api.account(initial="10000")
    budget = api.post("/budgets", {"category_id": api.category_id("Кафе"), "period": "month",
                                   "limit_amount": "5000"})
    rev = api.post("/sync", {"since_rev": 0, "changes": []})["rev"]
    changes = [
        _op("transaction", {"kind": "expense", "amount": "100", "account_id": account["id"],
                            "category_id": api.category_id("Кафе")}),
        _op("transaction", {"kind": "expense", "amount": "200", "account_id": account["id"],
                            "merchant": "Пятерочка"}),
        _op("transaction", {"kind": "income", "amount": "1000", "account_id": account["id"],
                            "category_id": api.category_id("Зарплата")}),
        _op("budget", {"category_id": budget["category_id"], "period": "month",
                       "limit_amount": "7000"}, item_id=budget["id"]),
    ]
    request = {"device_id": "device-0001", "since_rev": rev, "changes": changes}
    first = api.post("/sync", request)
    assert [r["status"] for r in first["results"]] == ["applied"] * 4
    assert api.get("/accounts")[0]["balance"] == "10700.00"
    assert api.get("/budgets")[0]["limit_amount"] == "7000.00"
    # Автоматическая категоризация операции, введенной без категории
    synced = {t["id"]: t for t in first["changes"]["transaction"]}
    assert synced[changes[1]["id"]]["category_id"] == api.category_id("Продукты")

    # Ответ сервера потерян (обрыв связи) -> устройство повторяет ту же отправку
    second = api.post("/sync", request)
    assert [r["status"] for r in second["results"]] == ["duplicate"] * 4
    assert api.get("/transactions")["total"] == 3  # дублирования нет
    assert api.get("/accounts")[0]["balance"] == "10700.00"
    status = api.get("/sync/status")
    assert status["rev"] == second["rev"] and status["last_status"] == "ok"


def test_conflict_is_resolved_in_favor_of_latest_change(make_api, client):
    """Одна и та же транзакция изменена на двух устройствах одной учетной записи."""
    api = make_api()
    account = api.account(initial="1000")
    transaction = api.spend(account["id"], 100, "Кафе", comment="исходная")
    base = {"kind": "expense", "account_id": account["id"], "category_id": api.category_id("Кафе")}
    # Устройство А (без сети) изменило запись раньше...
    offline_change = _op("transaction", {**base, "amount": "150", "comment": "с устройства А"},
                         item_id=transaction["id"], updated_at=_now(-120))
    # ...а устройство Б (в сети) – позже
    api.put(f"/transactions/{transaction['id']}", {**base, "amount": "170", "comment": "с устройства Б"})
    rev = api.get("/sync/status")["rev"]
    response = api.post("/sync", {"device_id": "device-000A", "since_rev": rev - 1,
                                  "changes": [offline_change]})
    assert response["results"][0]["status"] == "conflict"
    # Устройство А получает серверную версию; данные не потеряны и не задвоены
    server_copy = response["changes"]["transaction"][0]
    assert server_copy["amount"] == "170.00" and server_copy["comment"] == "с устройства Б"
    assert api.get("/transactions")["total"] == 1
    assert api.get("/accounts")[0]["balance"] == "830.00"

    # Более позднее изменение с устройства А применяется
    later = _op("transaction", {**base, "amount": "180", "comment": "с устройства А, позже"},
                item_id=transaction["id"], updated_at=_now())
    response = api.post("/sync", {"device_id": "device-000A", "since_rev": response["rev"],
                                  "changes": [later]})
    assert response["results"][0]["status"] == "applied"
    assert api.get("/transactions")["items"][0]["amount"] == "180.00"


def test_offline_delete_and_business_rule_rejection(api):
    account = api.account(initial="1000")
    transaction = api.spend(account["id"], 100, "Кафе")
    rev = api.get("/sync/status")["rev"]
    changes = [
        _op("transaction", {}, item_id=transaction["id"], deleted=True),
        _op("transaction", {"kind": "expense", "amount": "-5", "account_id": account["id"]}),
        _op("budget", {"category_id": api.category_id("Зарплата"), "period": "month",
                       "limit_amount": "100"}),
        _op("unknown", {"x": 1}),
    ]
    response = api.post("/sync", {"since_rev": rev, "changes": changes})
    statuses = {r["entity"] + ":" + r["id"]: r["status"] for r in response["results"]}
    assert statuses[f"transaction:{transaction['id']}"] == "applied"
    assert list(statuses.values()).count("rejected") == 3
    assert all(r["message"] for r in response["results"] if r["status"] == "rejected")
    assert api.get("/transactions")["total"] == 0
    assert response["changes"]["transaction"][0]["deleted_at"] is not None
    assert api.get("/sync/status")["last_status"] == "partial"


def test_changes_from_another_device_are_pulled(api):
    account = api.account()
    rev = api.post("/sync", {"since_rev": 0, "changes": []})["rev"]
    api.spend(account["id"], 55, "Кафе")  # изменение с другого устройства
    response = api.post("/sync", {"since_rev": rev, "changes": []})
    assert len(response["changes"]["transaction"]) == 1 and response["rev"] > rev
    assert api.post("/sync", {"since_rev": response["rev"], "changes": []})["changes"] == {}


def test_foreign_record_cannot_be_overwritten(make_api):
    owner, intruder = make_api(), make_api()
    account = owner.account()
    transaction = owner.spend(account["id"], 10, "Кафе")
    response = intruder.post("/sync", {"since_rev": 0, "changes": [
        _op("transaction", {"kind": "expense", "amount": "999", "account_id": account["id"]},
            item_id=transaction["id"])]})
    assert response["results"][0]["status"] == "rejected"
    assert owner.get("/transactions")["items"][0]["amount"] == "10.00"


def test_notification_channels_and_settings(api):
    account = api.account(initial="50000")
    api.post("/profile/devices", {"device_id": "device-0001", "push_token": "fcm-token-1",
                                  "model": "Xiaomi Redmi 14C"})
    api.patch("/profile", {"notification_settings": {"email_enabled": True,
                                                     "budget_threshold_percent": 60}})
    profile = api.get("/profile")
    assert profile["notification_settings"]["email_enabled"] is True
    assert profile["notification_settings"]["budget_threshold_percent"] == 60
    api.post("/budgets", {"category_id": api.category_id("Кафе"), "period": "month",
                          "limit_amount": "1000"})
    api.spend(account["id"], 700, "Кафе")  # 70 % > порога 60 % -> предупреждение (только push)
    api.spend(account["id"], 500, "Кафе")  # превышение -> push и письмо (важное уведомление)
    push = [m for m in notifier.outbox if m["channel"] == "push"]
    email = [m for m in notifier.outbox if m["channel"] == "email"]
    assert len(push) == 2 and push[0]["tokens"] == ["fcm-token-1"]
    assert len(email) == 1 and email[0]["to"] == api.email and "Превышен бюджет" in email[0]["title"]
    # Отключение уведомлений о бюджете
    api.patch("/profile", {"notification_settings": {"budget": False}})
    api.post("/budgets", {"category_id": api.category_id("Одежда"), "period": "month",
                          "limit_amount": "100"})
    api.spend(account["id"], 500, "Одежда")
    assert len([m for m in notifier.outbox if m["channel"] == "push"]) == 2
    first = api.get("/notifications", params={"unread_only": True})["items"]
    api.post(f"/notifications/{first[0]['id']}/read")
    assert len(api.get("/notifications", params={"unread_only": True})["items"]) == len(first) - 1


def test_push_is_handed_over_within_one_minute(api, db):
    from app.models import Notification

    account = api.account(initial="5000")
    api.post("/budgets", {"category_id": api.category_id("Кафе"), "period": "month",
                          "limit_amount": "100"})
    api.spend(account["id"], 200, "Кафе")
    notification = db.query(Notification).filter(Notification.user_id == api.user_id).one()
    assert notification.push_sent_at is not None
    assert (notification.push_sent_at - notification.event_at).total_seconds() < 60
