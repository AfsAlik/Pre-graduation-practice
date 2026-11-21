"""Подсистема учета транзакций и счетов (п. 4.2.1.1 ТЗ, п. 4.1.5 ПМИ)."""
from datetime import UTC, date, datetime, timedelta

from app.models import RecurringPayment
from app.services import scheduler


def test_manual_transaction_updates_balance_and_budget(api):
    account = api.account(initial="5000")
    food = api.category_id("Продукты")
    api.post("/budgets", {"category_id": food, "period": "month", "limit_amount": "10000"})
    transaction = api.spend(account["id"], "1500", "Продукты", comment="Закупка на неделю")
    assert transaction["kind"] == "expense" and transaction["currency"] == "RUB"
    assert api.get("/accounts")[0]["balance"] == "3500.00"
    assert api.get("/budgets/status")[0]["spent"] == "1500.00"


def test_validation_of_input_data(api):
    account = api.account()
    base = {"kind": "expense", "account_id": account["id"]}
    api.post("/transactions", {**base, "amount": "0"}, expected=422)
    api.post("/transactions", {**base, "amount": "-5"}, expected=422)
    api.post("/transactions", {**base, "amount": "abc"}, expected=422)
    api.post("/transactions", {"kind": "expense", "amount": "10", "account_id": "нет-такого"},
             expected=422)
    api.post("/transactions", {**base, "amount": "10",
                               "category_id": api.category_id("Зарплата")}, expected=422)
    api.post("/transactions", {"kind": "transfer", "amount": "10", "account_id": account["id"],
                               "to_account_id": account["id"]}, expected=422)


def test_auto_categorization_by_model_and_by_history(api):
    account = api.account()
    # По модели: категория определяется по наименованию торговой точки
    taxi = api.spend(account["id"], 350, merchant="YANDEX TAXI")
    assert taxi["auto_categorized"] and taxi["category_id"] == api.category_id("Транспорт")
    assert taxi["category_confidence"] > 0.3
    # По истории пользователя: собственный выбор категории запоминается
    custom = api.post("/categories", {"name": "Собака", "kind": "expense"})
    api.spend(account["id"], 900, merchant="Ветеринар Айболит", category_id=custom["id"])
    again = api.spend(account["id"], 700, merchant="Ветеринар Айболит")
    assert again["category_id"] == custom["id"] and again["category_confidence"] == 1.0
    suggestion = api.post("/transactions/suggest-category?kind=expense", {"text": "Ветеринар Айболит"})
    assert suggestion["category_id"] == custom["id"] and suggestion["source"] == "history"


def test_custom_category_and_subcategory(api):
    account = api.account()
    parent = api.post("/categories", {"name": "Хобби", "kind": "expense"})
    child = api.post("/categories", {"name": "Рыбалка", "kind": "expense", "parent_id": parent["id"]})
    transaction = api.spend(account["id"], 1200, category_id=child["id"])
    assert transaction["category_id"] == child["id"]
    api.post("/budgets", {"category_id": parent["id"], "period": "month", "limit_amount": "5000"})
    # Расход по подкатегории учитывается в лимите родительской категории
    assert api.get("/budgets/status")[0]["spent"] == "1200.00"
    # Системную категорию изменить нельзя
    api.put(f"/categories/{api.category_id('Продукты')}", {"name": "Еда", "kind": "expense"},
            expected=403)


def test_accounts_in_different_currencies_and_transfer(api):
    cash = api.account("Наличные", "cash", "RUB", "10000")
    bank = api.account("Карта", "bank", "RUB", "50000")
    usd = api.account("Валютный счет", "bank", "USD", "100")
    api.earn(bank["id"], 20000)  # пополнение
    api.spend(cash["id"], 2500, "Кафе")  # списание
    api.post("/transactions", {"kind": "transfer", "amount": "9000", "account_id": bank["id"],
                               "to_account_id": usd["id"]})  # 9000 RUB -> 100 USD по курсу 90
    accounts = {a["name"]: a for a in api.get("/accounts")}
    assert accounts["Наличные"]["balance"] == "7500.00"
    assert accounts["Карта"]["balance"] == "61000.00"
    assert accounts["Валютный счет"]["balance"] == "200.00"
    assert accounts["Валютный счет"]["balance_base"] == "18000.00"  # пересчет в базовую валюту
    assert api.get("/dashboard")["total_balance"] == "86500.00"


def test_amount_in_foreign_currency_is_converted_to_account_currency(api):
    account = api.account(initial="10000")
    transaction = api.post("/transactions", {"kind": "expense", "amount": "10", "currency": "USD",
                                             "account_id": account["id"]})
    assert transaction["amount"] == "900.00" and transaction["currency"] == "RUB"
    assert "10.00 USD" in transaction["comment"]


def test_history_filters_and_search(api):
    card, cash = api.account("Карта"), api.account("Наличные", "cash")
    now = datetime.now(UTC)
    api.spend(card["id"], 100, "Продукты", comment="молоко",
              occurred_at=(now - timedelta(days=40)).isoformat())
    api.spend(card["id"], 2500, "Кафе", comment="ужин в ресторане")
    api.spend(cash["id"], 300, "Транспорт", merchant="Такси")
    api.earn(card["id"], 50000)

    def ids(**params):
        return api.get("/transactions", params=params)

    assert ids()["total"] == 4
    week_ago = (date.today() - timedelta(days=7)).isoformat()
    assert ids(date_from=week_ago)["total"] == 3
    assert ids(category_id=api.category_id("Кафе"))["total"] == 1
    assert ids(account_id=cash["id"])["total"] == 1
    assert ids(amount_min="1000", amount_max="3000")["items"][0]["comment"] == "ужин в ресторане"
    assert ids(kind="income")["total"] == 1
    assert ids(search="рестор")["total"] == 1
    assert ids(search="такси")["total"] == 1
    page = ids(limit=2, offset=0)
    assert len(page["items"]) == 2 and page["total"] == 4


def test_edit_delete_and_restore(api):
    account = api.account(initial="1000")
    created = api.spend(account["id"], 400, "Продукты", comment="чек 17", merchant="Лента")
    updated = api.put(f"/transactions/{created['id']}", {
        "kind": "expense", "amount": "450", "account_id": account["id"],
        "category_id": api.category_id("Продукты"), "comment": "чек 17", "merchant": "Лента"})
    assert updated["amount"] == "450.00"
    assert api.get("/accounts")[0]["balance"] == "550.00"
    api.delete(f"/transactions/{created['id']}")
    assert api.get("/transactions")["total"] == 0
    assert api.get("/accounts")[0]["balance"] == "1000.00"
    assert api.get("/transactions", params={"deleted": True})["total"] == 1  # корзина
    restored = api.post(f"/transactions/{created['id']}/restore")
    for field in ("amount", "comment", "merchant", "category_id", "occurred_at", "account_id"):
        assert restored[field] == updated[field]
    assert api.get("/accounts")[0]["balance"] == "550.00"


def test_account_with_operations_cannot_be_deleted(api):
    account = api.account()
    api.spend(account["id"], 10, "Прочее")
    api.delete(f"/accounts/{account['id']}", expected=409)
    empty = api.account("Пустой")
    api.delete(f"/accounts/{empty['id']}")


def test_template_is_used_for_quick_input(api):
    account = api.account()
    template = api.post("/templates", {"name": "Обед", "kind": "expense", "amount": "350",
                                       "account_id": account["id"],
                                       "category_id": api.category_id("Кафе")})
    transaction = api.post("/transactions", {
        "kind": template["kind"], "amount": template["amount"], "account_id": template["account_id"],
        "category_id": template["category_id"], "source": "template"})
    assert transaction["source"] == "template" and transaction["amount"] == "350.00"


def test_receipt_text_is_parsed(api):
    text = ("ООО \"Агроторг\"\nПЯТЕРОЧКА\nКАССОВЫЙ ЧЕК №215\nМолоко 3,2% 89.99\nХлеб 45.00\n"
            "ИТОГ 134.99\nБЕЗНАЛИЧНЫМИ 134.99\n20.11.2026 18:42\nИНН 7825706086")
    parsed = api.post("/transactions/parse-receipt", data={"text": text},
                      files={"photo": ("receipt.jpg", b"\xff\xd8fake-jpeg", "image/jpeg")})
    assert parsed["amount"] == "134.99"
    assert parsed["occurred_at"].startswith("2026-11-20T18:42")
    assert parsed["category"]["category_name"] == "Продукты"
    assert parsed["receipt_file"].startswith(f"receipts/{api.user_id}/")
    qr = api.post("/transactions/parse-receipt",
                  data={"text": "t=20261120T1842&s=134.99&fn=9960440300&i=1&fp=2&n=1"})
    assert qr["amount"] == "134.99"


def test_voice_phrase_is_parsed(api):
    spent = api.post("/transactions/parse-voice", {"text": "Потратил 500 рублей на кафе"})
    assert spent["kind"] == "expense" and spent["amount"] == "500.00"
    assert spent["category"]["category_name"] == "Кафе"
    earned = api.post("/transactions/parse-voice", {"text": "получил зарплату 80 тысяч"})
    assert earned["kind"] == "income" and earned["amount"] == "80000.00"
    assert earned["category"]["category_name"] == "Зарплата"
    words = api.post("/transactions/parse-voice", {"text": "такси триста пятьдесят рублей"})
    assert words["amount"] == "350.00" and words["category"]["category_name"] == "Транспорт"


def test_recurring_payment_reminder(api, db):
    account = api.account()
    tomorrow = (date.today() + timedelta(days=1)).isoformat()
    payment = api.post("/recurring-payments", {
        "name": "Интернет", "amount": "650", "account_id": account["id"],
        "category_id": api.category_id("Связь"), "period": "month", "next_date": tomorrow,
        "remind_days_before": 1})
    assert scheduler.remind_recurring_payments(db) >= 1
    db.commit()
    notifications = [n for n in api.get("/notifications")["items"] if n["kind"] == "recurring_payment"]
    assert len(notifications) == 1 and "Интернет" in notifications[0]["title"]
    # Повторный запуск не создает второго напоминания о той же дате
    scheduler.remind_recurring_payments(db)
    db.commit()
    assert len([n for n in api.get("/notifications")["items"] if n["kind"] == "recurring_payment"]) == 1
    # После наступления даты платеж переносится на следующий период
    row = db.get(RecurringPayment, payment["id"])
    scheduler.remind_recurring_payments(db, today=date.today() + timedelta(days=3))
    db.commit()
    db.refresh(row)
    assert row.next_date > date.today() + timedelta(days=3)
