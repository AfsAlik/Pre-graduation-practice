"""Сценарии предварительных комплексных испытаний серверной части на тестовом стенде.

Сценарии повторяют операции методик программы и методики испытаний (ПМИ, п. 4.1.x)
и выполняются через программный интерфейс развернутой системы. Результат каждой
проверки записывается в файл JSON, по которому заполняется протокол испытаний.

Подготовка стенда:
    python -m app.manage seed-demo
Запуск:
    python standcheck/run_checks.py --base http://127.0.0.1:8000 \
        --mail-log var/worker.log --db-url postgresql://finhelper:finhelper@localhost/finhelper \
        --out var/standcheck.json

Параметры --mail-log и --db-url необязательны; без них пропускаются проверки,
которым нужны письма (стенд записывает их в журнал вместо отправки) и прямой
доступ к тестовой базе данных.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
import threading
import time
import uuid
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import httpx
import openpyxl
import pyotp

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.ml.control_set import CONTROL_SET  # noqa: E402

API = "/api/v1"
RESULTS: list[dict] = []


def record(stage: int, item: str, title: str, ok: bool | None, detail: str = "", **values) -> None:
    """Сохраняет результат проверки. ok=None – проверка не выполнялась."""
    status = "не выполнялась" if ok is None else ("да" if ok else "нет")
    RESULTS.append({"stage": stage, "item": item, "title": title, "result": status,
                    "detail": detail, **values})
    print(f"[этап {stage:>2} {item}] {status:<14} {title}" + (f" – {detail}" if detail else ""))


class Api:
    """Клиент программного интерфейса от имени одной учетной записи."""

    def __init__(self, base: str, email: str, password: str, totp: str | None = None):
        self.base, self.email, self.password = base, email, password
        self.http = httpx.Client(timeout=120)
        response = self.http.post(f"{base}{API}/auth/login",
                                  json={"email": email, "password": password, "totp_code": totp})
        response.raise_for_status()
        data = response.json()
        self.user_id, self.role = data["user_id"], data["role"]
        self.http.headers["Authorization"] = f"Bearer {data['access_token']}"

    def call(self, method: str, path: str, **kwargs) -> httpx.Response:
        return self.http.request(method, f"{self.base}{API}{path}", **kwargs)

    def get(self, path: str, **params):
        response = self.call("GET", path, params=params or None)
        response.raise_for_status()
        return response.json()

    def post(self, path: str, body=None, **kwargs):
        response = self.call("POST", path, json=body, **kwargs)
        response.raise_for_status()
        return response.json()

    def put(self, path: str, body):
        response = self.call("PUT", path, json=body)
        response.raise_for_status()
        return response.json()

    def category(self, name: str) -> str:
        return next(c["id"] for c in self.get("/categories") if c["name"] == name)

    def spend(self, account: str, amount, category: str | None = None, **extra):
        body = {"kind": "expense", "amount": str(amount), "account_id": account, **extra}
        if category:
            body["category_id"] = self.category(category)
        return self.post("/transactions", body)


class Stand:
    def __init__(self, args):
        self.args = args
        self.base = args.base.rstrip("/")
        self.admin = Api(self.base, args.admin, args.password)
        self.analyst = Api(self.base, args.analyst, args.password)
        self.demo = Api(self.base, args.user, args.password)

    def new_user(self, role: str = "user") -> Api:
        """Создает тестовую учетную запись средствами администратора."""
        email = f"stand-{uuid.uuid4().hex[:10]}@example.com"
        password = "Stand-" + uuid.uuid4().hex[:10]
        self.admin.post("/admin/users", {"email": email, "role": role, "password": password,
                                         "display_name": "Стендовая учетная запись"})
        return Api(self.base, email, password)

    def mail(self, pattern: str, address: str, wait: float = 10.0) -> str | None:
        """Ищет в журнале исполнителя фоновых задач письмо для адреса и извлекает из него данные."""
        if not self.args.mail_log:
            return None
        deadline = time.monotonic() + wait
        while time.monotonic() < deadline:
            text = Path(self.args.mail_log).read_text(encoding="utf-8", errors="replace")
            for line in reversed(text.splitlines()):
                if f"EMAIL -> {address}" in line:
                    match = re.search(pattern, line)
                    if match:
                        return match.group(1)
            time.sleep(0.5)
        return None

    def sql(self, statement: str, *params):
        import psycopg

        with psycopg.connect(self.args.db_url, autocommit=True) as connection:
            cursor = connection.execute(statement, params)
            return cursor.fetchall() if cursor.description else None

    def wait_notifications(self, api: Api, kind: str, count: int, wait: float = 15.0) -> list[dict]:
        deadline = time.monotonic() + wait
        items: list[dict] = []
        while time.monotonic() < deadline:
            items = [n for n in api.get("/notifications")["items"] if n["kind"] == kind]
            if len(items) >= count and all(n["push_sent_at"] for n in items):
                break
            time.sleep(0.5)
        return items


def handover_seconds(notification: dict) -> float:
    sent = datetime.fromisoformat(notification["push_sent_at"])
    return (sent - datetime.fromisoformat(notification["created_at"])).total_seconds()


# ----------------------------------------------------------------------------- этап 1
def stage_1(stand: Stand) -> None:
    http = httpx.Client(timeout=30)
    ready = http.get(f"{stand.base}/health/ready").json()
    record(1, "а", "Серверное приложение доступно, база данных подключена",
           ready.get("database") is True)
    paths = http.get(f"{stand.base}/openapi.json").json()["paths"]
    groups = {
        "учета транзакций и счетов": ["/transactions", "/accounts", "/categories"],
        "бюджетирования и целей": ["/budgets/status", "/goals/progress"],
        "инвестиционного учета": ["/investments/portfolio", "/investments/trades"],
        "аналитики и отчетности": ["/dashboard", "/reports"],
        "интеллектуальных рекомендаций": ["/recommendations", "/forecast", "/ml/quality"],
        "уведомлений и синхронизации": ["/sync", "/notifications"],
        "интеграции и импорта/экспорта": ["/import/statement", "/transactions/export", "/rates"],
        "администрирования и безопасности": ["/auth/login", "/admin/users", "/admin/logs"],
    }
    missing = [name for name, required in groups.items()
               if any(f"{API}{p}" not in paths for p in required)]
    record(1, "б", "Программный интерфейс всех восьми подсистем опубликован", not missing,
           f"адресов в спецификации: {len(paths)}" + (f"; нет: {missing}" if missing else ""))
    stand.admin.post("/admin/jobs/health_check/run")
    time.sleep(3)
    monitoring = stand.admin.get("/admin/monitoring", hours=1)
    components = monitoring["components"]
    record(1, "в", "Компоненты серверной части работоспособны по данным мониторинга",
           components["database"] and components["ml_service"],
           f"БД: {components['database']}, сервис ML: {components['ml_service']}, "
           f"курсы валют актуальны: {components['rates']}")
    quality = stand.analyst.get("/ml/quality")
    record(1, "г", "Сервис машинного обучения имеет активную модель категоризации",
           bool(quality["model"] and quality["model"]["is_active"]),
           f"версия {quality['model']['version']}" if quality["model"] else "")


# ----------------------------------------------------------------------------- этап 5
def stage_5(stand: Stand) -> None:
    user = stand.new_user()
    card = user.post("/accounts", {"name": "Карта", "type": "bank", "initial_balance": "50000"})
    user.post("/budgets", {"category_id": user.category("Продукты"), "period": "month",
                           "limit_amount": "20000"})
    started = time.monotonic()
    created = user.spend(card["id"], 1500, "Продукты")
    elapsed = time.monotonic() - started
    balance = user.get("/accounts")[0]["balance"]
    spent = user.get("/budgets/status")[0]["spent"]
    record(5, "а", "Ручной ввод расхода: транзакция сохранена, баланс и бюджет обновлены",
           created["amount"] == "1500.00" and balance == "48500.00" and spent == "1500.00",
           f"баланс {balance}, израсходовано по бюджету {spent}, время ответа {elapsed:.3f} с")

    template = user.post("/templates", {"name": "Обед", "kind": "expense", "amount": "450",
                                        "account_id": card["id"],
                                        "category_id": user.category("Кафе")})
    by_template = user.post("/transactions", {
        "kind": "expense", "amount": template["amount"], "account_id": card["id"],
        "category_id": template["category_id"], "source": "template"})
    receipt_text = ('ООО "Агроторг"\nПЯТЕРОЧКА\nКАССОВЫЙ ЧЕК №215\nМолоко 3,2% 89.99\nХлеб 45.00\n'
                    "ИТОГ 134.99\nБЕЗНАЛИЧНЫМИ 134.99\n20.11.2026 18:42")
    receipt = user.post("/transactions/parse-receipt", data={"text": receipt_text})
    voice = user.post("/transactions/parse-voice", {"text": "потратил 500 рублей на кафе"})
    ok = (by_template["source"] == "template" and receipt["amount"] == "134.99"
          and receipt["category"]["category_name"] == "Продукты"
          and voice["amount"] == "500.00" and voice["category"]["category_name"] == "Кафе")
    record(5, "б", "Быстрый ввод: шаблон, текст чека, голосовая фраза", ok,
           f"чек: {receipt['amount']} «{receipt['category']['category_name']}»; фраза: "
           f"{voice['amount']} «{voice['category']['category_name']}». Распознавание фотографии "
           "и речи на устройстве не проверялось")

    # в) не менее 50 транзакций с эталонными категориями
    evaluated = stand.analyst.post("/ml/evaluate", {"samples": CONTROL_SET})
    names = {c["id"]: c["name"] for c in user.get("/categories")}
    roots = {c["id"]: c for c in user.get("/categories")}

    def root_name(category_id: str | None) -> str | None:
        while category_id and roots[category_id]["parent_id"]:
            category_id = roots[category_id]["parent_id"]
        return names.get(category_id) if category_id else None

    correct = 0
    for sample in CONTROL_SET:
        saved = user.post("/transactions", {"kind": sample["kind"], "amount": "100",
                                            "account_id": card["id"], "merchant": sample["text"]})
        correct += root_name(saved["category_id"]) == sample["category"]
    accuracy = correct / len(CONTROL_SET)
    record(5, "в", "Точность автоматической категоризации на контрольной выборке не менее 80 %",
           accuracy >= 0.80,
           f"{correct} из {len(CONTROL_SET)} операций ({accuracy * 100:.1f} %); оценка модели: "
           f"accuracy {evaluated['accuracy']:.3f}, precision {evaluated['precision']:.3f}, "
           f"recall {evaluated['recall']:.3f}, F1 {evaluated['f1']:.3f}",
           accuracy=round(accuracy, 4), correct=correct, total=len(CONTROL_SET),
           errors=evaluated["errors"])

    parent = user.post("/categories", {"name": "Хобби", "kind": "expense"})
    child = user.post("/categories", {"name": "Рыбалка", "kind": "expense",
                                      "parent_id": parent["id"]})
    hobby = user.post("/transactions", {"kind": "expense", "amount": "1200",
                                        "account_id": card["id"], "category_id": child["id"]})
    record(5, "г", "Пользовательская категория и подкатегория", hobby["category_id"] == child["id"])

    multi = stand.new_user()
    cash = multi.post("/accounts", {"name": "Наличные", "type": "cash", "initial_balance": "10000"})
    bank = multi.post("/accounts", {"name": "Карта", "type": "bank", "initial_balance": "50000"})
    usd = multi.post("/accounts", {"name": "Валютный", "type": "bank", "currency": "USD",
                                   "initial_balance": "100"})
    multi.post("/transactions", {"kind": "income", "amount": "20000", "account_id": bank["id"],
                                 "category_id": multi.category("Зарплата")})
    multi.spend(cash["id"], 2500, "Кафе")
    rate = Decimal(multi.get("/rates")["rates"]["USD"])
    transfer = multi.post("/transactions", {"kind": "transfer", "amount": "9000",
                                            "account_id": bank["id"], "to_account_id": usd["id"]})
    accounts = {a["name"]: a for a in multi.get("/accounts")}
    expected_usd = Decimal("100") + (Decimal("9000") / rate).quantize(Decimal("0.01"))
    expected_base = (expected_usd * rate).quantize(Decimal("0.01"))
    total = Decimal(multi.get("/dashboard")["total_balance"])
    ok = (accounts["Наличные"]["balance"] == "7500.00" and accounts["Карта"]["balance"] == "61000.00"
          and Decimal(accounts["Валютный"]["balance"]) == expected_usd
          and abs(Decimal(accounts["Валютный"]["balance_base"]) - expected_base) <= Decimal("0.01"))
    record(5, "д", "Три счета разных типов и валют: пополнение, списание, перевод, пересчет", ok,
           f"курс USD {rate}; зачислено {transfer['amount_to']} USD; баланс валютного счета "
           f"{accounts['Валютный']['balance']} USD = {accounts['Валютный']['balance_base']} руб.; "
           f"общий баланс {total} руб.")

    now = datetime.now(UTC)
    multi.spend(bank["id"], 100, "Продукты", comment="молоко",
                occurred_at=(now - timedelta(days=40)).isoformat())
    multi.spend(bank["id"], 2600, "Кафе", comment="ужин в ресторане")
    week_ago = (date.today() - timedelta(days=7)).isoformat()
    checks = {
        "период": multi.get("/transactions", date_from=week_ago)["total"] == 4,
        "категория": multi.get("/transactions", category_id=multi.category("Кафе"))["total"] == 2,
        "счет": multi.get("/transactions", account_id=cash["id"])["total"] == 1,
        "сумма": multi.get("/transactions", amount_min="2550", amount_max="3000")["total"] == 1,
        "поиск": multi.get("/transactions", search="РЕСТОР")["total"] == 1,
    }
    record(5, "е", "Фильтры по периоду, категории, счету и сумме, поиск", all(checks.values()),
           ", ".join(f"{k}: {'да' if v else 'нет'}" for k, v in checks.items()))

    before = multi.get("/transactions", search="ресторане")["items"][0]
    multi.call("DELETE", f"/transactions/{before['id']}").raise_for_status()
    in_trash = multi.get("/transactions", deleted=True)["total"]
    restored = multi.post(f"/transactions/{before['id']}/restore")
    same = all(restored[f] == before[f] for f in ("amount", "comment", "category_id", "account_id",
                                                  "occurred_at", "kind"))
    record(5, "ж", "Удаление и восстановление транзакции со всеми атрибутами",
           in_trash == 1 and same, f"в корзине после удаления: {in_trash}")

    multi.post("/recurring-payments", {
        "name": "Интернет", "amount": "650", "account_id": bank["id"], "period": "month",
        "category_id": multi.category("Связь"),
        "next_date": (date.today() + timedelta(days=1)).isoformat(), "remind_days_before": 1})
    stand.admin.post("/admin/jobs/recurring_reminders/run")
    reminders = stand.wait_notifications(multi, "recurring_payment", 1)
    record(5, "з", "Регулярный платеж: напоминание о дате платежа", len(reminders) == 1,
           reminders[0]["title"] if reminders else "напоминание не получено")


# ----------------------------------------------------------------------------- этап 6
def stage_6(stand: Stand) -> None:
    user = stand.new_user()
    card = user.post("/accounts", {"name": "Карта", "type": "bank", "initial_balance": "200000"})
    budget = user.post("/budgets", {"category_id": user.category("Кафе"), "period": "month",
                                    "limit_amount": "10000"})
    for name, period in (("Продукты", "week"), ("Транспорт", "month"), ("Развлечения", "year"),
                         ("Одежда", "month")):
        user.post("/budgets", {"category_id": user.category(name), "period": period,
                               "limit_amount": "5000"})
    record(6, "а", "Установка лимита 10 000 руб. на «Кафе» и еще четырех лимитов на разные периоды",
           len(user.get("/budgets")) == 5)

    user.spend(card["id"], 8000, "Кафе")
    state = user.get(f"/budgets/{budget['id']}/status")
    warnings = stand.wait_notifications(user, "budget_warning", 1)
    record(6, "б", "Расходы 8 000 руб.: уведомление о приближении к лимиту",
           state["status"] == "ok" and state["warning"] and len(warnings) == 1,
           f"израсходовано {state['percent']} %, статус «{state['status_title']}»; уведомление "
           f"передано в сервис доставки через {handover_seconds(warnings[0]):.2f} с"
           if warnings else "уведомление не сформировано")

    user.spend(card["id"], 3000, "Кафе")
    state = user.get(f"/budgets/{budget['id']}/status")
    exceeded = stand.wait_notifications(user, "budget_exceeded", 1)
    record(6, "в", "Расход еще 3 000 руб.: уведомление о превышении, статус «превышение»",
           state["status"] == "exceeded" and len(exceeded) == 1,
           f"израсходовано {state['percent']} %, статус «{state['status_title']}», остаток "
           f"{state['remaining']} руб. Выделение цветом на экране не проверялось")

    deadline = date.today() + timedelta(days=183)
    goal = user.post("/goals", {"name": "Отпуск", "target_amount": "100000",
                                "deadline": deadline.isoformat(), "priority": "high"})
    record(6, "г", "Создание цели «Отпуск»: 100 000 руб., 6 месяцев, высокий приоритет",
           goal["status"] == "active" and goal["priority"] == "high")
    user.post("/goal-contributions", {"goal_id": goal["id"], "amount": "40000"})
    progress = user.get(f"/goals/{goal['id']}/progress")
    record(6, "д", "Накопление 40 000 руб.: прогресс 40 %, прогноз даты, рекомендуемая сумма",
           progress["percent"] == 40.0 and progress["forecast_date"] is not None
           and Decimal(progress["recommended_monthly"]) > 0,
           f"прогресс {progress['percent']} %, прогнозируемая дата {progress['forecast_date']}, "
           f"рекомендуемая сумма {progress['recommended_monthly']} руб. в месяц")
    user.post("/goal-contributions", {"goal_id": goal["id"], "amount": "60000"})
    progress = user.get(f"/goals/{goal['id']}/progress")
    edit = user.call("PUT", f"/goals/{goal['id']}", json={
        "name": "Отпуск 2", "target_amount": "150000", "deadline": deadline.isoformat()})
    achieved = stand.wait_notifications(user, "goal_achieved", 1)
    record(6, "е", "Достигнутая цель доступна только для просмотра",
           progress["read_only"] and edit.status_code == 409 and len(achieved) == 1,
           f"статус цели «{progress['goal']['status']}», попытка изменения отклонена "
           f"(код {edit.status_code}): {edit.json().get('detail')}")


# ----------------------------------------------------------------------------- этап 7
def moex_price(http: httpx.Client, market: str, board: str, ticker: str) -> Decimal | None:
    """Независимый запрос цены у Московской биржи (для сверки с данными системы)."""
    data = http.get(
        f"https://iss.moex.com/iss/engines/stock/markets/{market}/boards/{board}/securities/"
        f"{ticker}.json", params={"iss.meta": "off", "iss.only": "marketdata,securities",
                                  "marketdata.columns": "SECID,LAST,LCURRENTPRICE,MARKETPRICE",
                                  "securities.columns": "SECID,PREVPRICE,FACEVALUE"}).json()
    if not data["securities"]["data"]:
        return None
    market_row = data["marketdata"]["data"][0] if data["marketdata"]["data"] else [None] * 4
    price = market_row[1] or market_row[2] or market_row[3] or data["securities"]["data"][0][1]
    price = Decimal(str(price))
    if market == "bonds":
        price = price * Decimal(str(data["securities"]["data"][0][2])) / 100
    return price


def stage_7(stand: Stand) -> None:
    user = stand.new_user()
    trades = [("stock", "SBER", "Сбербанк", "10", "250", "25"),
              ("stock", "LKOH", "Лукойл", "2", "6500", "30"),
              ("etf", "TMOS", "Фонд Индекс МосБиржи", "1000", "6.5", "0"),
              ("bond", "SU26238RMFS4", "ОФЗ 26238", "5", "560", "14"),
              ("currency", "USD", "Доллар США", "100", "88", "0"),
              ("crypto", "BTC", "Биткойн", "0.01", "8500000", "100")]
    for asset_type, ticker, name, quantity, price, fee in trades:
        user.post("/investments/trades", {"asset_type": asset_type, "ticker": ticker, "name": name,
                                          "quantity": quantity, "price": price, "fee": fee,
                                          "traded_at": (datetime.now(UTC)
                                                        - timedelta(days=20)).isoformat()})
    time.sleep(8)  # фоновые задачи получают котировки после регистрации сделок
    started = time.monotonic()
    portfolio = user.get("/investments/portfolio")
    with_refresh = time.monotonic() - started
    started = time.monotonic()
    user.get("/investments/portfolio", refresh=False)
    saved_only = time.monotonic() - started
    positions = {p["ticker"]: p for p in portfolio["positions"]}
    types = {p["asset_type"] for p in portfolio["positions"]}
    record(7, "а", "Добавлено шесть позиций пяти типов активов",
           len(positions) == 6 and len(types) == 5, f"типы: {', '.join(sorted(types))}")

    priced = {t: p["current_price"] for t, p in positions.items() if p["price_as_of"]}
    http = httpx.Client(timeout=30)
    source = {"SBER": moex_price(http, "shares", "TQBR", "SBER"),
              "LKOH": moex_price(http, "shares", "TQBR", "LKOH"),
              "TMOS": moex_price(http, "shares", "TQBR", "TMOS"),
              "SU26238RMFS4": moex_price(http, "bonds", "TQOB", "SU26238RMFS4")}
    deviations = {t: abs(Decimal(priced[t]) - source[t]) / source[t] * 100
                  for t in source if t in priced and source[t]}
    record(7, "б", "Текущие рыночные цены получены из внешних источников",
           len(priced) == 6 and all(d <= 1 for d in deviations.values()),
           "; ".join(f"{t}: {priced.get(t, 'нет')}" for t in positions)
           + f". Наибольшее расхождение с ценой биржи при сверке: {max(deviations.values()):.2f} %")

    # Независимый контрольный расчет по методу средней себестоимости
    rates = user.get("/rates")["rates"]
    invested = sum(Decimal(q) * Decimal(p) + Decimal(f) for _, _, _, q, p, f in trades)
    value = sum(Decimal(q) * Decimal(positions[t]["current_price"]) for _, t, _, q, _, _ in trades)
    profit = value - invested
    d_invested = abs(Decimal(portfolio["total_invested_base"]) - invested) / invested * 100
    d_value = abs(Decimal(portfolio["total_value_base"]) - value) / value * 100
    d_profit = abs(Decimal(portfolio["total_profit_base"]) - profit) / abs(profit) * 100
    record(7, "в", "Отклонение от независимого контрольного расчета не более 1 %",
           max(d_invested, d_value, d_profit) <= 1,
           f"система: вложено {portfolio['total_invested_base']}, стоимость "
           f"{portfolio['total_value_base']}, результат {portfolio['total_profit_base']} руб. "
           f"({portfolio['total_profit_percent']} %); контрольный расчет: вложено {invested:.2f}, "
           f"стоимость {value:.2f}, результат {profit:.2f} руб.; отклонения "
           f"{d_invested:.3f} / {d_value:.3f} / {d_profit:.3f} %",
           portfolio_seconds_saved=round(saved_only, 3), portfolio_seconds_refresh=round(with_refresh, 3))
    del rates

    points = {period: len(user.get("/investments/dynamics", period=period)["points"])
              for period in ("day", "month", "year")}
    record(7, "г", "График динамики за день, месяц и год, структура портфеля",
           all(points.values()) and abs(sum(portfolio["structure"].values()) - 100) < 0.5,
           f"точек графика: {points}; структура: {portfolio['structure']}")
    details = user.get("/investments/assets/SBER")
    record(7, "д", "Карточка актива: история цен и доходность за периоды",
           len(details["history"]) > 100 and details["returns"]["month"] is not None,
           f"SBER: {len(details['history'])} значений истории, доходность за неделю/месяц/квартал/год: "
           f"{details['returns']['week']} / {details['returns']['month']} / "
           f"{details['returns']['quarter']} / {details['returns']['year']} %")

    if stand.args.db_url:
        trade = next(t for t in user.get("/investments/trades") if t["ticker"] == "SBER")
        stand.sql("UPDATE investment_trades SET created_at = now() - interval '8 days' WHERE id = %s",
                  trade["id"])
        response = user.call("PUT", f"/investments/trades/{trade['id']}", json={
            "asset_type": "stock", "ticker": "SBER", "name": "Сбербанк", "quantity": "15",
            "price": "250", "fee": "25"})
        record(7, "е", "Изменение позиции старше 7 дней заблокировано с пояснением",
               response.status_code == 409 and "7 дней" in response.json()["detail"],
               f"код {response.status_code}: {response.json()['detail']}")
    else:
        record(7, "е", "Изменение позиции старше 7 дней заблокировано с пояснением", None,
               "нет доступа к тестовой БД для изменения даты фиксации")
    return saved_only, with_refresh


# ----------------------------------------------------------------------------- этап 8
def stage_8(stand: Stand) -> None:
    user = stand.demo
    started = time.monotonic()
    dashboard = user.get("/dashboard")
    elapsed = time.monotonic() - started
    blocks = ("total_balance", "income", "expense", "expense_chart", "expenses_by_category",
              "budget", "goal", "portfolio", "forecast")
    present = [b for b in blocks if dashboard.get(b) not in (None, [], {})]
    record(8, "а", "Главный экран содержит все блоки сводной информации",
           len(present) == len(blocks),
           f"баланс {dashboard['total_balance']}, доходы {dashboard['income']}, расходы "
           f"{dashboard['expense']} руб.; время ответа {elapsed:.3f} с")
    month_start = date.today().replace(day=1).isoformat()
    today = date.today().isoformat()
    started = time.monotonic()
    report = user.get("/reports", start=month_start, end=today)
    report_seconds = time.monotonic() - started
    record(8, "б", "Отчет по категориям расходов за текущий месяц сформирован",
           bool(report["expenses_by_category"]) and bool(report["summary"]),
           f"категорий: {len(report['expenses_by_category'])}; время ответа {report_seconds:.3f} с")
    items = user.get("/transactions", date_from=month_start, date_to=today, limit=500)["items"]
    income = sum(Decimal(i["amount"]) for i in items if i["kind"] == "income")
    expense = sum(Decimal(i["amount"]) for i in items if i["kind"] == "expense")
    record(8, "в", "Итоги отчета совпадают с данными транзакций",
           Decimal(report["income"]) == income and Decimal(report["expense"]) == expense,
           f"отчет: доходы {report['income']}, расходы {report['expense']}; по транзакциям: "
           f"{income:.2f} и {expense:.2f} ({len(items)} операций)")
    account = next(a for a in user.get("/accounts") if a["name"] == "Наличные")
    by_account = user.get("/reports", start=month_start, end=today, account_id=account["id"])
    by_category = user.get("/reports", start=month_start, end=today,
                           category_id=user.category("Продукты"))
    record(8, "г", "Фильтры по счету и категории изменяют данные отчета",
           Decimal(by_account["expense"]) < expense and 0 < Decimal(by_category["expense"]) < expense,
           f"по счету «Наличные»: {by_account['expense']}; по категории «Продукты»: "
           f"{by_category['expense']} руб.")
    forecast = dashboard["forecast"]
    record(8, "д", "Прогноз остатка средств на конец месяца отображается",
           forecast["forecast_balance"] is not None,
           f"остаток {forecast['current_balance']}, прогноз {forecast['forecast_balance']} руб. "
           f"через {forecast['days_left']} дн.")


# ----------------------------------------------------------------------------- этап 9
def stage_9(stand: Stand) -> None:
    user = stand.demo
    started = time.monotonic()
    items = user.get("/recommendations")["items"]
    recommendations_seconds = time.monotonic() - started
    complete = all(i["text"] and i["explanation"] and i["estimated_saving"] is not None for i in items)
    record(9, "а", "Рекомендации с пояснением и оценкой экономии (история не менее 6 месяцев)",
           bool(items) and complete,
           "; ".join(f"«{i['title']}» ({i['estimated_saving']} руб.)" for i in items[:4])
           + f"; время ответа {recommendations_seconds:.3f} с")
    # Учетная запись с историей за шесть месяцев и без бюджетных лимитов
    fresh = stand.new_user()
    card = fresh.post("/accounts", {"name": "Карта", "type": "bank", "initial_balance": "300000"})
    for back in range(6, 0, -1):
        moment = (datetime.now(UTC) - timedelta(days=30 * back)).isoformat()
        fresh.post("/transactions", {"kind": "income", "amount": "80000", "account_id": card["id"],
                                     "category_id": fresh.category("Зарплата"), "occurred_at": moment})
        fresh.spend(card["id"], 15000 + back * 300, "Продукты", occurred_at=moment)
        fresh.spend(card["id"], 9000, "Кафе", occurred_at=moment)
    own = fresh.get("/recommendations")["items"]
    actionable = next((i for i in own if i["action"].get("type") == "set_budget"), None)
    if actionable:
        rated = fresh.post(f"/recommendations/{own[-1]['id']}/feedback", {"useful": True})
        fresh.post(f"/recommendations/{actionable['id']}/apply")
        stored = {i["id"]: i for i in fresh.get("/recommendations", refresh=False)["items"]}
        budgets = fresh.get("/budgets")
        ok = (stored[own[-1]["id"]]["feedback"] == "useful"
              and stored[actionable["id"]]["status"] == "applied" and len(budgets) == 1
              and budgets[0]["limit_amount"] == actionable["action"]["limit_amount"])
        record(9, "б", "Оценка «Полезно» и применение рекомендации кнопкой «Установить бюджет»", ok,
               f"оценка сохранена: {rated['feedback']}; рекомендация «{actionable['title']}» применена: "
               f"создан лимит {budgets[0]['limit_amount']} руб., оценка экономии "
               f"{actionable['estimated_saving']} руб. в месяц")
    else:
        record(9, "б", "Оценка «Полезно» и применение рекомендации кнопкой «Установить бюджет»", False,
               "рекомендация с действием «Установить бюджет» не сформирована")
    started = time.monotonic()
    forecast = user.get("/forecast")
    forecast_seconds = time.monotonic() - started
    expense = forecast["expense"]
    record(9, "в", "Прогноз расходов и доходов на следующий месяц с интервалом уверенности",
           expense["lower"] <= expense["forecast"] <= expense["upper"]
           and expense["upper"] > expense["lower"],
           f"расходы: {expense['forecast']} руб., интервал {expense['lower']}–{expense['upper']} руб., "
           f"метод {expense['method']}, ошибка на истории {expense['mape']} %; доходы: "
           f"{forecast['income']['forecast']} руб.; время ответа {forecast_seconds:.3f} с")

    risky = stand.new_user()
    card = risky.post("/accounts", {"name": "Карта", "type": "bank", "initial_balance": "0"})
    for back in (75, 45, 15):
        moment = (datetime.now(UTC) - timedelta(days=back)).isoformat()
        risky.post("/transactions", {"kind": "income", "amount": "80000", "account_id": card["id"],
                                     "category_id": risky.category("Зарплата"), "occurred_at": moment})
        risky.spend(card["id"], 36000, "Кредиты", occurred_at=moment)
        risky.spend(card["id"], 40000, "Продукты", occurred_at=moment)
    risks = {r["kind"]: r for r in risky.get("/risks")["items"]}
    record(9, "г", "Предупреждение о риске: кредитные платежи более 40 % дохода, нет резерва",
           set(risks) == {"debt_load", "reserve_fund"},
           f"доля кредитных платежей {risks.get('debt_load', {}).get('value', 0) * 100:.0f} %; "
           f"резерв покрывает {risks.get('reserve_fund', {}).get('value', 0):.1f} мес.")

    # Ежесуточная задача рассчитывает ошибку прогноза и сохраняет срез показателей качества
    stand.admin.post("/admin/jobs/daily/run")
    quality = stand.analyst.get("/ml/quality")
    deadline = time.monotonic() + 180
    while quality["forecast_mape"] is None and time.monotonic() < deadline:
        time.sleep(3)
        quality = stand.analyst.get("/ml/quality")
    model = quality["model"]
    record(9, "д", "Аналитику отображаются показатели качества и их динамика",
           all(0 < model[k] <= 1 for k in ("accuracy", "precision", "recall", "f1"))
           and bool(quality["history"]) and quality["forecast_mape"] is not None,
           f"accuracy {model['accuracy']}, precision {model['precision']}, recall {model['recall']}, "
           f"F1 {model['f1']}; точность на новых торговых точках {model['unseen_accuracy']}; "
           f"средняя ошибка прогноза расходов {quality['forecast_mape']} %; доля положительных "
           f"оценок {quality['positive_feedback_share']} (оценено рекомендаций: "
           f"{quality['rated_recommendations']}); срезов динамики: {len(quality['history'])}",
           model=model, quality=quality)
    started_model = stand.analyst.post("/ml/models/train")
    began = time.monotonic()
    state = started_model
    while state["status"] == "training" and time.monotonic() - began < 7200:
        time.sleep(2)
        state = stand.analyst.get(f"/ml/models/{started_model['id']}")
    record(9, "е", "Повторное обучение модели завершено не более чем за 2 часа, показатели обновлены",
           state["status"] == "ready" and state["duration_seconds"] <= 7200,
           f"версия {state['version']}: обучение {state['duration_seconds']:.1f} с на "
           f"{state['samples']} примерах, accuracy {state['accuracy']}, активна: {state['is_active']}",
           retrain_seconds=state["duration_seconds"], retrain_model=state)


# ----------------------------------------------------------------------------- этап 10
def sync_op(entity: str, data: dict, *, item_id=None, deleted=False, shift=0) -> dict:
    return {"op_id": str(uuid.uuid4()), "entity": entity, "id": item_id or str(uuid.uuid4()),
            "updated_at": (datetime.now(UTC) + timedelta(seconds=shift)).isoformat(),
            "deleted": deleted, "data": data}


def stage_10(stand: Stand) -> None:
    user = stand.new_user()
    user.post("/profile/devices", {"device_id": str(uuid.uuid4()), "push_token": "stand-device-token",
                                   "model": "стенд"})
    profile = user.call("PATCH", "/profile", json={"notification_settings": {
        "email_enabled": True, "budget_threshold_percent": 70}}).json()
    settings = profile["notification_settings"]
    record(10, "а", "Настройки уведомлений: включена почта, изменен порог бюджета",
           settings["email_enabled"] and settings["budget_threshold_percent"] == 70)

    card = user.post("/accounts", {"name": "Карта", "type": "bank", "initial_balance": "10000"})
    budget = user.post("/budgets", {"category_id": user.category("Кафе"), "period": "month",
                                    "limit_amount": "5000"})
    existing = user.spend(card["id"], 100, "Кафе", comment="исходная")
    rev = user.get("/sync/status")["rev"]
    # Операции, накопленные устройством без подключения к сети
    offline = [
        sync_op("transaction", {"kind": "expense", "amount": "200", "account_id": card["id"],
                                "merchant": "Пятерочка"}),
        sync_op("transaction", {"kind": "expense", "amount": "300", "account_id": card["id"],
                                "category_id": user.category("Транспорт")}),
        sync_op("transaction", {"kind": "income", "amount": "1000", "account_id": card["id"],
                                "category_id": user.category("Зарплата")}),
        sync_op("budget", {"category_id": budget["category_id"], "period": "month",
                           "limit_amount": "7000"}, item_id=budget["id"]),
        sync_op("transaction", {"kind": "expense", "amount": "150", "account_id": card["id"],
                                "category_id": user.category("Кафе"), "comment": "с устройства А"},
                item_id=existing["id"], shift=-120),
    ]
    # То же самое изменено на втором устройстве, подключенном к сети, позже
    user.put(f"/transactions/{existing['id']}", {
        "kind": "expense", "amount": "170", "account_id": card["id"],
        "category_id": user.category("Кафе"), "comment": "с устройства Б"})
    request = {"device_id": "stand-device-a", "since_rev": rev, "changes": offline}
    started = time.monotonic()
    first = user.post("/sync", request)
    sync_seconds = time.monotonic() - started
    statuses = [r["status"] for r in first["results"]]
    record(10, "б", "Операции автономного режима приняты после восстановления связи",
           statuses.count("applied") == 4,
           f"результаты: {statuses}; время синхронизации {sync_seconds:.3f} с", sync_seconds=sync_seconds)
    server_copy = next(t for t in first["changes"]["transaction"] if t["id"] == existing["id"])
    record(10, "в", "Конфликт двух устройств разрешен: применено последнее по времени изменение",
           statuses.count("conflict") == 1 and server_copy["comment"] == "с устройства Б",
           f"устройству возвращена серверная версия: {server_copy['amount']} руб., "
           f"«{server_copy['comment']}»")
    second = user.post("/sync", request)  # повторная отправка той же очереди (потерян ответ)
    total = user.get("/transactions")["total"]
    balance = user.get("/accounts")[0]["balance"]
    record(10, "г", "Повторная отправка очереди не создает дубликатов, данные не потеряны",
           all(r["status"] == "duplicate" for r in second["results"]) and total == 4
           and balance == "10330.00",
           f"операций в истории: {total}, баланс {balance} руб., лимит "
           f"{user.get('/budgets')[0]['limit_amount']} руб.")

    user.spend(card["id"], 7500, "Кафе")  # превышение лимита 7 000 руб.
    exceeded = stand.wait_notifications(user, "budget_exceeded", 1)
    letter = stand.mail(r"(Превышен бюджет[^|]*)", user.email)
    ok = bool(exceeded) and exceeded[0]["push_sent_at"] and exceeded[0]["email_sent_at"]
    record(10, "д", "Уведомление о превышении лимита передано по каналам push и e-mail", bool(ok),
           (f"push передан через {handover_seconds(exceeded[0]):.2f} с; письмо: "
            f"{'«' + letter.strip() + '»' if letter else 'записано в журнал'}. "
            "Доставка на устройство и в почтовый ящик не проверялась: на стенде каналы работают "
            "в режиме записи в журнал") if exceeded else "уведомление не сформировано")


# ----------------------------------------------------------------------------- этап 11
def statement_files(count: int) -> dict[str, bytes]:
    """Формирует тестовые выписки одного содержания в трех форматах."""
    merchants = [("PYATEROCHKA 1234 EKATERINBURG", "-1250.50"), ("YANDEX TAXI", "-380.00"),
                 ("Зачисление заработной платы", "85000.00"), ("APTEKA RIGLA", "-642.30"),
                 ("DODO PIZZA", "-899.00")]
    start = datetime(2026, 10, 1, 9, 0)
    csv_rows = ["Дата операции;Описание;Сумма операции;Валюта"]
    ofx = ["OFXHEADER:100", "DATA:OFXSGML", "<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>RUB",
           "<BANKTRANLIST>"]
    qif = ["!Type:Bank"]
    for index in range(count):
        name, amount = merchants[index % len(merchants)]
        moment = start + timedelta(minutes=37 * index)
        name = f"{name} {index:04d}"
        csv_rows.append(f"{moment:%d.%m.%Y %H:%M};{name};{amount.replace('.', ',')};RUB")
        ofx.append(f"<STMTTRN><DTPOSTED>{moment:%Y%m%d%H%M%S}<TRNAMT>{amount}<FITID>S{index}"
                   f"<NAME>{name}</STMTTRN>")
        qif += [f"D{moment:%d/%m/%Y}", f"T{amount}", f"P{name}", "^"]
    ofx.append("</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>")
    return {"statement.csv": "\n".join(csv_rows).encode("cp1251"),
            "statement.ofx": "\n".join(ofx).encode("utf-8"),
            "statement.qif": "\n".join(qif).encode("utf-8")}


def stage_11(stand: Stand) -> None:
    results = {}
    for name, content in statement_files(1000).items():
        user = stand.new_user()
        account = user.post("/accounts", {"name": "Карта", "type": "bank"})
        started = time.monotonic()
        response = user.post("/import/statement", data={"account_id": account["id"]},
                             files={"file": (name, content, "application/octet-stream")})
        seconds = time.monotonic() - started
        page = user.get("/transactions", limit=500)
        income = user.get("/transactions", kind="income", limit=1)["total"]
        first = user.get("/transactions", search="PYATEROCHKA 1234 EKATERINBURG 0000")["items"][0]
        again = user.post("/import/statement", data={"account_id": account["id"]},
                          files={"file": (name, content, "application/octet-stream")})
        results[name] = (response["imported"] == 1000 and page["total"] == 1000 and income == 200
                         and first["amount"] == "1250.50" and first["occurred_at"].startswith("2026-10-01")
                         and again["imported"] == 0, seconds, user, account)
    record(11, "а", "Импорт выписок CSV, OFX, QIF по 1 000 операций: суммы, даты, назначения совпадают",
           all(r[0] for r in results.values()),
           "; ".join(f"{n.split('.')[1].upper()}: {r[1]:.1f} с" for n, r in results.items())
           + "; повторный импорт дубликатов не создает",
           import_seconds={n: round(r[1], 2) for n, r in results.items()})

    user = results["statement.csv"][2]
    started = time.monotonic()
    xlsx = user.call("GET", "/transactions/export", params={"format": "xlsx", "start": "2026-10-01",
                                                            "end": "2026-10-31"})
    xlsx_seconds = time.monotonic() - started
    started = time.monotonic()
    pdf = user.call("GET", "/transactions/export", params={"format": "pdf", "start": "2026-10-01",
                                                           "end": "2026-10-31"})
    pdf_seconds = time.monotonic() - started
    rows = list(openpyxl.load_workbook(io.BytesIO(xlsx.content)).active.iter_rows(values_only=True))
    header = next(i for i, r in enumerate(rows) if r[0] == "Дата")
    exported = len(rows) - header - 1
    expected = user.get("/transactions", date_from="2026-10-01", date_to="2026-10-31", limit=1)["total"]
    record(11, "б", "Экспорт истории операций за месяц в PDF и XLSX: данные полные",
           exported == expected and pdf.content.startswith(b"%PDF"),
           f"XLSX: {exported} строк из {expected} операций за {xlsx_seconds:.1f} с; PDF: "
           f"{len(pdf.content) // 1024} КБ за {pdf_seconds:.1f} с",
           export_seconds={"xlsx": round(xlsx_seconds, 2), "pdf": round(pdf_seconds, 2)})

    http = httpx.Client(timeout=30)
    system = stand.admin.get("/rates", refresh=True)
    cbr = http.get("https://www.cbr.ru/scripts/XML_daily.asp").content.decode("cp1251")
    source = {code: Decimal(value.replace(",", ".")) / Decimal(nominal) for code, nominal, value in
              re.findall(r"<CharCode>(\w+)</CharCode><Nominal>(\d+)</Nominal>.*?<Value>([\d,]+)</Value>",
                         cbr)}
    same = all(abs(Decimal(system["rates"][c]) - source[c]) < Decimal("0.0001")
               for c in ("USD", "EUR", "CNY"))
    record(11, "в", "Курсы валют совпадают с данными Банка России", same and not system["stale"],
           f"на {system['as_of']}: USD {system['rates']['USD']}, EUR {system['rates']['EUR']}, "
           f"CNY {system['rates']['CNY']}; котировки сверены на этапе 7")

    if stand.args.broken_base:
        broken = Api(stand.args.broken_base.rstrip("/"), stand.args.admin, stand.args.password)
        failed = broken.get("/rates", refresh=True)
        record(11, "г", "При недоступности источника используются сохраненные курсы с датой актуальности",
               failed.get("ok") is False and failed["rates"]["USD"] == system["rates"]["USD"]
               and failed["as_of"] == system["as_of"],
               f"источник недоступен; использованы курсы на {failed['as_of']}: USD {failed['rates']['USD']}")
    else:
        record(11, "г", "При недоступности источника используются сохраненные курсы", None,
               "не задан экземпляр серверного приложения с недоступным источником")

    user = stand.new_user()
    denied = user.call("POST", "/bank-connections", json={"provider": "demo-bank"})
    user.post("/bank-consents", {"provider": "demo-bank"})
    allowed = user.call("POST", "/bank-connections", json={"provider": "demo-bank"})
    record(11, "д", "Подключение источника банковских данных без согласия невозможно",
           denied.status_code == 403 and allowed.status_code == 200,
           f"без согласия: код {denied.status_code}; после согласия: код {allowed.status_code}")


# ----------------------------------------------------------------------------- этап 12
def stage_12(stand: Stand) -> None:
    http = httpx.Client(timeout=30)
    email = f"stand-{uuid.uuid4().hex[:10]}@example.com"
    password = "Stand-pass-2026"
    body = {"email": email, "password": password, "display_name": "Новый пользователь"}
    without = http.post(f"{stand.base}{API}/auth/register", json={**body, "consent_personal_data": False})
    with_consent = http.post(f"{stand.base}{API}/auth/register", json={**body, "consent_personal_data": True})
    before_confirm = http.post(f"{stand.base}{API}/auth/login", json={"email": email, "password": password})
    token = stand.mail(r"confirm-email\?token=([\w\-]+)", email)
    confirmed = http.get(f"{stand.base}{API}/auth/confirm-email", params={"token": token}) if token else None
    record(12, "а", "Регистрация: без согласия невозможна; адрес подтверждается по ссылке из письма",
           without.status_code == 422 and with_consent.status_code == 201
           and before_confirm.status_code == 403
           and (confirmed is None or confirmed.status_code == 200) and token is not None,
           f"без согласия: код {without.status_code}; с согласием: {with_consent.status_code}; вход до "
           f"подтверждения: {before_confirm.status_code}; подтверждение: "
           f"{confirmed.status_code if confirmed else 'письмо не найдено в журнале'}")

    http.post(f"{stand.base}{API}/auth/password/forgot", json={"email": email})
    reset_token = stand.mail(r"код: ([\w\-]+)", email)
    new_password = "Stand-new-2027"
    reset = http.post(f"{stand.base}{API}/auth/password/reset",
                      json={"token": reset_token, "new_password": new_password}) if reset_token else None
    user = Api(stand.base, email, new_password) if reset is not None and reset.status_code == 200 else None
    two_factor = False
    if user:
        secret = user.post("/auth/2fa/setup")["secret"]
        user.post("/auth/2fa/enable", {"code": pyotp.TOTP(secret).now()})
        no_code = http.post(f"{stand.base}{API}/auth/login", json={"email": email, "password": new_password})
        with_code = http.post(f"{stand.base}{API}/auth/login", json={
            "email": email, "password": new_password, "totp_code": pyotp.TOTP(secret).now()})
        two_factor = no_code.status_code == 401 and with_code.status_code == 200
    record(12, "б", "Восстановление пароля по временной ссылке и двухфакторная аутентификация",
           user is not None and two_factor,
           "пароль изменен по коду из письма; вход без одноразового кода отклонен, с кодом выполнен"
           if user else "письмо с кодом восстановления не найдено")

    victim = stand.new_user()
    codes = [http.post(f"{stand.base}{API}/auth/login",
                       json={"email": victim.email, "password": "wrong-password-1"}).status_code
             for _ in range(5)]
    locked = http.post(f"{stand.base}{API}/auth/login",
                       json={"email": victim.email, "password": victim.password})
    record(12, "в", "После пяти неверных паролей вход блокируется на 15 минут",
           codes == [401] * 5 and locked.status_code == 423,
           f"коды попыток: {codes}; вход с верным паролем: {locked.status_code} – "
           f"{locked.json().get('detail')}")

    plain = stand.new_user()
    denied = {path: plain.call("GET", path).status_code for path in
              ("/admin/users", "/admin/logs", "/admin/monitoring", "/admin/settings", "/ml/quality",
               "/ml/models")}
    analyst_denied = stand.analyst.call("GET", "/admin/users").status_code
    record(12, "г", "Роль «Пользователь» не имеет доступа к административным разделам",
           set(denied.values()) == {403} and analyst_denied == 403,
           f"коды ответов: {sorted(set(denied.values()))}; аналитику управление учетными записями "
           f"недоступно (код {analyst_denied})")

    created = stand.admin.post("/admin/users", {"email": f"stand-{uuid.uuid4().hex[:10]}@example.com",
                                                "role": "analyst", "display_name": "Новый аналитик"})
    blocked = stand.admin.post(f"/admin/users/{created['id']}/block")
    blocked_login = http.post(f"{stand.base}{API}/auth/login", json={
        "email": created["email"], "password": created["temporary_password"]})
    reset_other = stand.admin.post(f"/admin/users/{plain.user_id}/reset-password")
    old_login = http.post(f"{stand.base}{API}/auth/login",
                          json={"email": plain.email, "password": plain.password})
    new_login = http.post(f"{stand.base}{API}/auth/login",
                          json={"email": plain.email, "password": reset_other["temporary_password"]})
    record(12, "д", "Администратор создает учетную запись, блокирует ее, сбрасывает пароль другой",
           created["role"] == "analyst" and blocked["is_blocked"] and blocked_login.status_code == 403
           and old_login.status_code == 401 and new_login.status_code == 200,
           f"вход заблокированной учетной записи: код {blocked_login.status_code}; после сброса: "
           f"старый пароль – {old_login.status_code}, временный – {new_login.status_code}")

    today = date.today().isoformat()
    logs = stand.admin.get("/admin/logs", user=plain.email, date_from=today, date_to=today)
    exported = stand.admin.call("GET", "/admin/logs/export", params={"user": plain.email})
    lines = exported.content.decode("utf-8-sig").splitlines()
    external = ("at;journal;user_email;action;entity;entity_id;ip;details\n"
                "2026-10-01T10:00:00+00:00;auth;legacy@example.com;legacy.login;;;10.0.0.1;{}\n"
                "2026-10-01T10:05:00+00:00;data;legacy@example.com;legacy.payment;payment;77;10.0.0.1;{}\n")
    imported = stand.admin.post("/admin/logs/import",
                                files={"file": ("external.csv", external.encode(), "text/csv")})
    record(12, "е", "Журнал аудита: фильтр по пользователю и периоду, экспорт и импорт CSV",
           logs["total"] >= 2 and len(lines) == logs["total"] + 1 and imported["imported"] == 2,
           f"записей по пользователю за день: {logs['total']}; строк в файле экспорта: "
           f"{len(lines) - 1}; импортировано внешних записей: {imported['imported']}")

    monitoring = stand.admin.get("/admin/monitoring", hours=24)
    record(12, "ж", "Раздел «Мониторинг»: доступность, время ответа, ошибки",
           monitoring["requests"] > 0 and monitoring["availability_chart"] != [],
           f"доступность {monitoring['availability_percent']} % по {monitoring['health_checks']} "
           f"проверкам; запросов {monitoring['requests']}, среднее время ответа "
           f"{monitoring['average_response_ms']} мс, ошибок сервера {monitoring['server_errors']}",
           monitoring=monitoring)

    if stand.args.db_url:
        hashes = [row[0] for row in stand.sql(
            "SELECT password_hash FROM users WHERE email NOT LIKE %s", "%finhelper.local")]
        argon = sum(1 for h in hashes if h.startswith("$argon2"))
        record(12, "з", "Пароли хранятся в базе данных только в виде хеша",
               argon == len(hashes), f"учетных записей: {len(hashes)}, хешей Argon2: {argon}. "
               "Проверка трафика анализатором и шифрования локальной БД на устройстве не выполнялась")
    else:
        record(12, "з", "Пароли хранятся в базе данных только в виде хеша", None,
               "нет доступа к тестовой БД")


# ----------------------------------------------------------------------------- этап 15 (часть)
def stage_15_admins(stand: Stand) -> None:
    second_admin = stand.new_user("admin")
    target = stand.new_user()
    codes: list[int] = []

    def change(admin: Api, payload: dict) -> None:
        codes.append(admin.call("PATCH", f"/admin/users/{target.user_id}", json=payload).status_code)

    threads = [threading.Thread(target=change, args=(stand.admin, {"display_name": "Имя от первого"})),
               threading.Thread(target=change, args=(second_admin, {"role": "analyst"}))]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    state = stand.admin.get("/admin/users", search=target.email)["items"][0]
    record(15, "е", "Два администратора одновременно изменяют одну учетную запись: данные не потеряны",
           codes == [200, 200] and state["display_name"] == "Имя от первого" and state["role"] == "analyst",
           f"коды ответов {codes}; имя «{state['display_name']}», роль «{state['role_title']}»")


def main() -> None:
    parser = argparse.ArgumentParser(description="Сценарии испытаний на тестовом стенде")
    parser.add_argument("--base", default="http://127.0.0.1:8000")
    parser.add_argument("--admin", default="admin@example.com")
    parser.add_argument("--analyst", default="analyst@example.com")
    parser.add_argument("--user", default="user@example.com")
    parser.add_argument("--password", default="Demo-2026-pass")
    parser.add_argument("--mail-log", default="")
    parser.add_argument("--db-url", default="")
    parser.add_argument("--broken-base", default="")
    parser.add_argument("--out", default="var/standcheck.json")
    parser.add_argument("--stages", default="1,5,6,7,8,9,10,11,12,15")
    args = parser.parse_args()


    stand = Stand(args)
    stages = {1: stage_1, 5: stage_5, 6: stage_6, 7: stage_7, 8: stage_8, 9: stage_9, 10: stage_10,
              11: stage_11, 12: stage_12, 15: stage_15_admins}
    started = datetime.now(UTC)
    for number in (int(s) for s in args.stages.split(",")):
        try:
            stages[number](stand)
        except Exception as exc:  # ошибка сценария фиксируется как отрицательный результат
            record(number, "–", "Сценарий этапа прерван ошибкой", False, f"{type(exc).__name__}: {exc}")
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps({
        "started_at": started.isoformat(), "finished_at": datetime.now(UTC).isoformat(),
        "base": args.base, "results": RESULTS}, ensure_ascii=False, indent=1, default=str),
        encoding="utf-8")
    failed = [r for r in RESULTS if r["result"] == "нет"]
    print(f"\nПроверок: {len(RESULTS)}, не пройдено: {len(failed)}, результаты: {args.out}")


if __name__ == "__main__":
    main()
