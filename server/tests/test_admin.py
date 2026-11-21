"""Администрирование, журналы, мониторинг и управление моделями (п. 4.1.9, 4.1.12 ПМИ)."""
import threading
from datetime import date

from app.main import API_PREFIX
from app.models import HealthCheck, MlModel
from app.services import monitoring, scheduler
from tests.conftest import PASSWORD


def test_admin_manages_accounts(client, make_api):
    admin, user = make_api("admin"), make_api("user")
    created = admin.post("/admin/users", {"email": f"analyst-{user.user_id[:8]}@example.com",
                                          "display_name": "Новый аналитик", "role": "analyst"})
    assert created["role"] == "analyst" and created["role_title"] == "Аналитик"
    assert created["temporary_password"]
    login = client.post(f"{API_PREFIX}/auth/login", json={"email": created["email"],
                                                         "password": created["temporary_password"]})
    assert login.status_code == 200 and login.json()["role"] == "analyst"

    listing = admin.get("/admin/users", params={"search": "новый аналитик"})
    assert listing["total"] == 1 and listing["items"][0]["id"] == created["id"]
    admin.post("/admin/users", {"email": created["email"], "role": "user"}, expected=409)
    admin.post("/admin/users", {"email": "x@example.com", "role": "root"}, expected=422)

    # Блокировка: вход и работа заблокированного пользователя невозможны
    assert admin.post(f"/admin/users/{user.user_id}/block")["is_blocked"] is True
    user.get("/accounts", expected=403)
    blocked = client.post(f"{API_PREFIX}/auth/login", json={"email": user.email, "password": PASSWORD})
    assert blocked.status_code == 403
    assert admin.post(f"/admin/users/{user.user_id}/unblock")["is_blocked"] is False
    user.get("/accounts")

    # Сброс пароля другой учетной записи
    reset = admin.post(f"/admin/users/{user.user_id}/reset-password")
    old = client.post(f"{API_PREFIX}/auth/login", json={"email": user.email, "password": PASSWORD})
    new = client.post(f"{API_PREFIX}/auth/login", json={"email": user.email,
                                                       "password": reset["temporary_password"]})
    assert old.status_code == 401 and new.status_code == 200
    # Администратор не может заблокировать сам себя или снять с себя роль
    admin.post(f"/admin/users/{admin.user_id}/block", expected=409)
    admin.patch(f"/admin/users/{admin.user_id}", {"role": "user"}, expected=409)
    assert admin.patch(f"/admin/users/{user.user_id}", {"role": "analyst"})["role"] == "analyst"


def test_audit_log_filter_export_import(make_api):
    admin, user = make_api("admin"), make_api("user")
    account = user.account()
    user.spend(account["id"], 100, "Кафе")
    user.delete(f"/accounts/{user.account('Лишний')['id']}")
    today = date.today().isoformat()

    by_user = admin.get("/admin/logs", params={"user": user.email, "date_from": today, "date_to": today})
    actions = [row["action"] for row in by_user["items"]]
    assert {"auth.login", "account.create", "transaction.create", "account.delete"} <= set(actions)
    assert all(row["user_email"] == user.email for row in by_user["items"])
    assert admin.get("/admin/logs", params={"user": user.email, "action": "transaction"})["total"] == 1
    assert admin.get("/admin/logs", params={"user": user.email, "journal": "auth"})["total"] == 1
    assert admin.get("/admin/logs", params={"user": user.email, "date_to": "2020-01-01"})["total"] == 0

    exported = admin.request("GET", "/admin/logs/export", 200, params={"user": user.email})
    text = exported.content.decode("utf-8-sig")
    assert text.splitlines()[0] == "at;journal;user_email;action;entity;entity_id;ip;details"
    assert len(text.splitlines()) == by_user["total"] + 1

    external = ("at;journal;user_email;action;entity;entity_id;ip;details\n"
                "2026-10-01T10:00:00+00:00;auth;legacy@example.com;legacy.login;;;10.0.0.1;{}\n"
                "2026-10-01T10:05:00+00:00;data;legacy@example.com;legacy.payment;payment;77;10.0.0.1;{}\n"
                "не дата;data;x;y;;;;\n")
    result = admin.request("POST", "/admin/logs/import", 200,
                           files={"file": ("external.csv", external.encode(), "text/csv")}).json()
    assert result["imported"] == 2
    imported = admin.get("/admin/logs", params={"origin": "import", "user": "legacy@example.com"})
    assert imported["total"] == 2 and {r["action"] for r in imported["items"]} == {
        "legacy.login", "legacy.payment"}
    # Действия администратора также фиксируются
    admin_actions = admin.get("/admin/logs", params={"user": admin.email, "journal": "admin"})
    assert {"admin.logs_export", "admin.logs_import"} <= {r["action"] for r in admin_actions["items"]}


def test_monitoring_summary(make_api, db):
    admin = make_api("admin")
    monitoring.run_health_check(db)
    db.add(HealthCheck(database_ok=True, ml_ok=False, rates_ok=True))  # имитация сбоя компонента
    db.commit()
    admin.get("/accounts")
    summary = admin.get("/admin/monitoring", params={"hours": 1})
    assert summary["requests"] >= 1 and summary["average_response_ms"] > 0
    assert 0 < summary["availability_percent"] < 100
    assert summary["availability_chart"] and "database" in summary["components"]
    assert summary["server_errors"] == 0
    metrics = admin.client.get("/metrics").text
    assert "fh_http_requests_total" in metrics and "fh_component_up" in metrics


def test_client_errors_and_support(make_api):
    admin, user = make_api("admin"), make_api("user")
    user.post("/client-errors", {"kind": "SyncException", "message": "Не удалось синхронизировать",
                                 "context": {"screen": "Транзакции"}}, expected=201)
    errors = admin.get("/admin/errors", params={"source": "client"})["items"]
    assert any(e["kind"] == "SyncException" and e["context"]["user_id"] == user.user_id for e in errors)
    assert len(user.get("/support/faq")["items"]) >= 5
    user.post("/support/requests", {"subject": "Вопрос по импорту", "message": "Не загружается выписка"},
              expected=201)
    assert any(r["subject"] == "Вопрос по импорту"
               for r in admin.get("/admin/support-requests")["items"])


def test_system_settings_and_maintenance_mode(make_api):
    admin, user = make_api("admin"), make_api("user")
    settings = admin.get("/admin/settings")
    assert {"integrations", "notifications", "backup", "maintenance"} <= set(settings)
    admin.put("/admin/settings/backup", {"value": {"cron": "30 2 * * *", "keep_days": 120}})
    assert admin.get("/admin/settings")["backup"]["cron"] == "30 2 * * *"
    admin.put("/admin/settings/unknown", {"value": {}}, expected=404)
    user.get("/admin/settings", expected=403)

    from app import main

    admin.put("/admin/settings/maintenance", {"value": {"enabled": True, "message": "Плановые работы"}})
    main._maintenance["at"] = 0.0  # сброс кеша признака обслуживания
    try:
        response = user.request("GET", "/accounts")
        assert response.status_code == 503 and "Плановые работы" in response.json()["detail"]
        assert user.get("/system/status")["maintenance"] is True  # информирование пользователей
        admin.get("/admin/settings")  # административные функции доступны
    finally:
        admin.put("/admin/settings/maintenance", {"value": {"enabled": False, "message": ""}})
        main._maintenance["at"] = 0.0
    user.get("/accounts")


def test_concurrent_admin_updates_do_not_lose_data(make_api):
    """Два администратора одновременно изменяют одну и ту же учетную запись."""
    first, second, target = make_api("admin"), make_api("admin"), make_api("user")
    results = []

    def change(admin, payload):
        results.append(admin.request("PATCH", f"/admin/users/{target.user_id}", json=payload).status_code)

    threads = [threading.Thread(target=change, args=(first, {"display_name": "Имя от первого"})),
               threading.Thread(target=change, args=(second, {"role": "analyst"}))]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert results == [200, 200]
    state = next(u for u in first.get("/admin/users", params={"search": target.email})["items"])
    assert state["display_name"] == "Имя от первого" and state["role"] == "analyst"


def test_analyst_quality_dashboard_and_retraining(make_api, db):
    analyst, user = make_api("analyst"), make_api("user")
    account = user.account()
    user.spend(account["id"], 500, "Здоровье", merchant="Клиника Здоровье Плюс")  # обезличенный пример
    quality = analyst.get("/ml/quality")
    for key in ("accuracy", "precision", "recall", "f1", "unseen_accuracy"):
        assert 0 < quality["model"][key] <= 1
    assert quality["model"]["accuracy"] >= quality["min_accuracy"] == 0.80
    assert "positive_feedback_share" in quality and "forecast_mape" in quality
    assert quality["history"] and quality["history"][-1]["accuracy"] > 0
    data = analyst.get("/ml/training-data")
    assert data["total"] >= 1 and any("клиника" in r["text"] for r in data["recent"])
    assert all("user" not in r and "amount" not in r for r in data["recent"])

    before = analyst.get("/ml/models")["items"]
    started = analyst.post("/ml/models/train", expected=202)
    assert started["version"] == before[0]["version"] + 1
    trained = analyst.get(f"/ml/models/{started['id']}")
    assert trained["status"] == "ready" and trained["is_active"] is True
    assert trained["trained_at"] is not None and trained["duration_seconds"] < 2 * 3600
    assert trained["samples"] > 0 and trained["accuracy"] >= 0.80
    models = analyst.get("/ml/models")["items"]
    assert sum(1 for m in models if m["is_active"]) == 1

    # Возврат к предыдущей версии, экспорт и импорт файла модели
    previous = next(m for m in models if m["id"] == before[0]["id"])
    assert analyst.post(f"/ml/models/{previous['id']}/activate")["is_active"] is True
    exported = analyst.request("GET", f"/ml/models/{trained['id']}/export", 200)
    assert len(exported.content) > 10000
    imported = analyst.request("POST", "/ml/models/import", 201, files={
        "file": ("model.joblib", exported.content, "application/octet-stream")}).json()
    assert imported["status"] == "ready" and imported["is_active"] is False
    assert db.query(MlModel).filter(MlModel.is_active.is_(True)).count() == 1
    make_api("user").post("/ml/models/train", expected=403)


def test_expired_data_is_purged(make_api, db):
    from datetime import timedelta

    from app.models import Transaction, utcnow

    user = make_api()
    account = user.account()
    old, fresh = user.spend(account["id"], 1, "Кафе"), user.spend(account["id"], 2, "Кафе")
    user.delete(f"/transactions/{old['id']}")
    user.delete(f"/transactions/{fresh['id']}")
    db.get(Transaction, old["id"]).deleted_at = utcnow() - timedelta(days=31)
    db.commit()
    scheduler.purge_expired(db)
    db.commit()
    # Через 30 дней удаленная транзакция уничтожается, более свежая остается в корзине
    assert db.get(Transaction, old["id"]) is None
    assert db.get(Transaction, fresh["id"]) is not None


def test_admin_runs_periodic_job_on_demand(make_api):
    from datetime import date, timedelta

    admin, user = make_api("admin"), make_api("user")
    account = user.account()
    user.post("/recurring-payments", {
        "name": "Подписка", "amount": "199", "account_id": account["id"], "period": "month",
        "next_date": (date.today() + timedelta(days=1)).isoformat(), "remind_days_before": 2})
    admin.post("/admin/jobs/recurring_reminders/run", expected=202)
    reminders = [n for n in user.get("/notifications")["items"] if n["kind"] == "recurring_payment"]
    assert len(reminders) == 1 and reminders[0]["push_sent_at"] is not None
    admin.post("/admin/jobs/unknown/run", expected=404)
    user.post("/admin/jobs/daily/run", expected=403)
    push = admin.get("/admin/monitoring", params={"hours": 1})["push"]
    assert push["handed_over"] >= 1 and push["within_minute_percent"] == 100.0
