"""Нагрузочное тестирование серверной части (п. 4.1.14, 4.1.15 программы и методики испытаний).

Профиль нагрузки: каждый виртуальный пользователь входит в систему и выполняет
типовой сценарий (добавление транзакции, просмотр главного экрана и отчета,
запрос рекомендаций и прогноза, синхронизация) с интервалом около 10 секунд
между запросами. При 100 пользователях это соответствует примерно 10 запросам
в секунду.

Подготовка:  python -m app.manage seed-load --users 200
Основной тест (100 пользователей, 30 минут):
    locust -f loadtest/locustfile.py --headless -H http://localhost:8000 \
           -u 100 -r 10 -t 30m --csv var/load/main --csv-full-history
Ступенчатый тест (50, 100 и 200 пользователей по 10 минут):
    FH_LOAD_SHAPE=steps locust -f loadtest/locustfile.py --headless -H http://localhost:8000 \
           --csv var/load/steps --csv-full-history
"""
import itertools
import os
import random
import uuid
from datetime import UTC, datetime

from locust import HttpUser, LoadTestShape, between, task

API = "/api/v1"
PASSWORD = os.getenv("FH_LOAD_PASSWORD", "Demo-2026-pass")
USERS = int(os.getenv("FH_LOAD_USERS", "200"))
_numbers = itertools.cycle(range(1, USERS + 1))
MERCHANTS = ["Пятерочка", "Магнит", "Яндекс Такси", "Шоколадница", "Лукойл АЗС", "Аптека Ригла",
             "Ozon", "ВкусВилл", "Кинопоиск", "Додо Пицца"]


class FinHelperUser(HttpUser):
    wait_time = between(8, 12)

    def on_start(self) -> None:
        email = f"load{next(_numbers):03d}@example.com"
        response = self.client.post(f"{API}/auth/login", json={"email": email, "password": PASSWORD},
                                    name="POST /auth/login")
        response.raise_for_status()
        self.client.headers["Authorization"] = f"Bearer {response.json()['access_token']}"
        accounts = self.client.get(f"{API}/accounts", name="GET /accounts").json()
        self.account_id = accounts[0]["id"]
        self.rev = self.client.get(f"{API}/sync/status", name="GET /sync/status").json()["rev"]

    # --- основные запросы: сохранение и получение данных (п. 4.2.2.2 ТЗ, не более 2 с) ---
    @task(5)
    def add_transaction(self) -> None:
        self.client.post(f"{API}/transactions", name="POST /transactions", json={
            "kind": "expense", "amount": str(random.randint(100, 3000)),
            "account_id": self.account_id, "merchant": random.choice(MERCHANTS)})

    @task(3)
    def dashboard(self) -> None:
        self.client.get(f"{API}/dashboard", name="GET /dashboard")

    @task(2)
    def transactions(self) -> None:
        self.client.get(f"{API}/transactions?limit=50", name="GET /transactions")

    @task(2)
    def budgets(self) -> None:
        self.client.get(f"{API}/budgets/status", name="GET /budgets/status")

    @task(1)
    def goals(self) -> None:
        self.client.get(f"{API}/goals/progress", name="GET /goals/progress")

    @task(1)
    def sync(self) -> None:
        change = {"op_id": str(uuid.uuid4()), "entity": "transaction", "id": str(uuid.uuid4()),
                  "updated_at": datetime.now(UTC).isoformat(), "deleted": False,
                  "data": {"kind": "expense", "amount": "150", "account_id": self.account_id,
                           "merchant": "Столовая"}}
        response = self.client.post(f"{API}/sync", name="POST /sync",
                                    json={"device_id": "load-test", "since_rev": self.rev,
                                          "changes": [change]})
        if response.ok:
            self.rev = response.json()["rev"]

    # --- аналитический отчет (п. 4.2.2.8 ТЗ, не более 5 с) ---
    @task(3)
    def report(self) -> None:
        self.client.get(f"{API}/reports", name="GET /reports")

    # --- рекомендации и прогноз (п. 4.2.2.9 ТЗ, не более 10 с) ---
    @task(2)
    def recommendations(self) -> None:
        self.client.get(f"{API}/recommendations", name="GET /recommendations")

    @task(1)
    def forecast(self) -> None:
        self.client.get(f"{API}/forecast", name="GET /forecast")

    # --- портфель по сохраненным котировкам (п. 4.2.2.7 ТЗ, не более 3 с) ---
    @task(1)
    def portfolio(self) -> None:
        self.client.get(f"{API}/investments/portfolio?refresh=false", name="GET /investments/portfolio")


if os.getenv("FH_LOAD_SHAPE") == "steps":

    class StepLoad(LoadTestShape):
        """Ступенчатое увеличение нагрузки: 50, 100 и 200 пользователей по 10 минут."""

        step_seconds = int(os.getenv("FH_LOAD_STEP_SECONDS", "600"))
        steps = [50, 100, 200]

        def tick(self):
            index = int(self.get_run_time() // self.step_seconds)
            if index >= len(self.steps):
                return None
            return self.steps[index], 20
