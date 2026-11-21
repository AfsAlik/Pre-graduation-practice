"""Подсистема инвестиционного учета (п. 4.2.1.3 ТЗ, п. 4.1.7 ПМИ)."""
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from app.models import InvestmentTrade
from app.services.portfolio import calculate_position
from app.services.quotes import STATIC_QUOTES

TRADES = [
    ("stock", "SBER", "Сбербанк", "10", "250", "25"),
    ("stock", "GAZP", "Газпром", "20", "150", "30"),
    ("etf", "TMOS", "Фонд Т-Капитал Индекс МосБиржи", "1000", "6.5", "0"),
    ("bond", "SU26238RMFS4", "ОФЗ 26238", "5", "560", "14"),
    ("currency", "USD", "Доллар США", "100", "88", "0"),
    ("crypto", "BTC", "Биткойн", "0.01", "8500000", "100"),
]


def _add(api, asset_type, ticker, name, quantity, price, fee, side="buy", **extra):
    return api.post("/investments/trades", {
        "asset_type": asset_type, "ticker": ticker, "name": name, "side": side,
        "quantity": quantity, "price": price, "fee": fee, **extra})


def test_portfolio_matches_independent_control_calculation(api):
    for trade in TRADES:
        _add(api, *trade)
    portfolio = api.get("/investments/portfolio")
    assert len(portfolio["positions"]) == 6
    assert {p["asset_type"] for p in portfolio["positions"]} == {"stock", "etf", "bond", "currency", "crypto"}

    # Независимый контрольный расчет по методу средней себестоимости
    invested = sum(Decimal(q) * Decimal(p) + Decimal(f) for _, _, _, q, p, f in TRADES)
    value = sum(Decimal(q) * STATIC_QUOTES[t] for _, t, _, q, _, _ in TRADES)
    profit = value - invested
    assert abs(Decimal(portfolio["total_invested_base"]) - invested) <= invested * Decimal("0.01")
    assert abs(Decimal(portfolio["total_value_base"]) - value) <= value * Decimal("0.01")
    assert abs(Decimal(portfolio["total_profit_base"]) - profit) <= abs(profit) * Decimal("0.01")
    expected_percent = float(profit / invested * 100)
    assert abs(portfolio["total_profit_percent"] - expected_percent) < 0.01

    sber = next(p for p in portfolio["positions"] if p["ticker"] == "SBER")
    assert Decimal(sber["average_price"]) == Decimal("252.5")  # (10*250 + 25) / 10
    assert sber["current_value"] == "3000.00" and sber["profit"] == "475.00"
    assert abs(sber["profit_percent"] - 18.81) < 0.01
    assert abs(sum(portfolio["structure"].values()) - 100.0) < 0.5
    assert portfolio["quotes_stale"] is False


def test_average_cost_and_fifo_methods(api):
    _add(api, "stock", "SBER", "Сбербанк", "10", "100", "0",
         traded_at=(datetime.now(UTC) - timedelta(days=3)).isoformat())
    _add(api, "stock", "SBER", "Сбербанк", "10", "200", "0",
         traded_at=(datetime.now(UTC) - timedelta(days=2)).isoformat())
    _add(api, "stock", "SBER", "Сбербанк", "10", "250", "0", side="sell")
    average = api.get("/investments/portfolio")["positions"][0]
    # Средняя себестоимость: (1000 + 2000) / 20 = 150; продано 10 шт. -> результат 1000
    assert Decimal(average["average_price"]) == Decimal("150") and average["realized_profit"] == "1000.00"
    api.patch("/profile", {"costing_method": "fifo"})
    fifo = api.get("/investments/portfolio")["positions"][0]
    # FIFO: проданы первые 10 шт. по 100 -> результат 1500, остаток по 200
    assert Decimal(fifo["average_price"]) == Decimal("200") and fifo["realized_profit"] == "1500.00"


def test_cannot_sell_more_than_position(api):
    _add(api, "stock", "GAZP", "Газпром", "5", "100", "0")
    api.post("/investments/trades", {"asset_type": "stock", "ticker": "GAZP", "side": "sell",
                                     "quantity": "6", "price": "110"}, expected=422)


def test_position_is_locked_after_seven_days(api, db):
    trade = _add(api, "stock", "SBER", "Сбербанк", "10", "250", "0")
    body = {"asset_type": "stock", "ticker": "SBER", "name": "Сбербанк", "side": "buy",
            "quantity": "12", "price": "250", "fee": "0"}
    # В течение 7 дней изменение разрешено
    assert api.put(f"/investments/trades/{trade['id']}", body)["quantity"] == "12"
    row = db.get(InvestmentTrade, trade["id"])
    row.created_at = datetime.now(UTC) - timedelta(days=8)
    db.commit()
    response = api.request("PUT", f"/investments/trades/{trade['id']}", json={**body, "quantity": "15"})
    assert response.status_code == 409
    assert "7 дней" in response.json()["detail"] and "новой сделкой" in response.json()["detail"]
    api.request("PUT", f"/investments/trades/{trade['id']}", 409, json={**body, "price": "300"})
    api.delete(f"/investments/trades/{trade['id']}", expected=409)
    # Название можно уточнить, количество и цена остаются прежними
    renamed = api.put(f"/investments/trades/{trade['id']}", {**body, "name": "Сбербанк ао"})
    assert renamed["name"] == "Сбербанк ао" and renamed["locked"] is True
    # Корректировка оформляется новой сделкой
    _add(api, "stock", "SBER", "Сбербанк", "3", "260", "0")
    assert Decimal(api.get("/investments/portfolio")["positions"][0]["quantity"]) == Decimal("15")


def test_dynamics_and_asset_details(api):
    _add(api, "stock", "SBER", "Сбербанк", "10", "250", "10",
         traded_at=(datetime.now(UTC) - timedelta(days=20)).isoformat())
    api.get("/investments/portfolio")  # обновление цен
    for period in ("day", "month", "year"):
        points = api.get("/investments/dynamics", params={"period": period})["points"]
        assert points and Decimal(points[-1]["value"]) == Decimal("3000.00")
    details = api.get("/investments/assets/SBER")
    assert details["current_price"] is not None and details["fees"] == "10.00"
    assert details["history"] and set(details["returns"]) == {"week", "month", "quarter", "year"}
    api.get("/investments/assets/NOPE", expected=404)


def test_position_calculation_unit():
    class Trade:
        def __init__(self, side, quantity, price, fee, day):
            self.side, self.quantity, self.price, self.fee = side, quantity, price, fee
            self.traded_at = self.created_at = datetime(2026, 1, day, tzinfo=UTC)

    trades = [Trade("buy", "4", "10", "2", 1), Trade("buy", "6", "20", "3", 2),
              Trade("sell", "5", "30", "5", 3)]
    average = calculate_position(trades, "average")
    assert average["quantity"] == Decimal("5")
    assert average["invested"] == Decimal("82.5")  # (42 + 123) / 10 * 5
    assert average["realized"] == Decimal("62.5")  # 145 - 82.5
    fifo = calculate_position(trades, "fifo")
    assert fifo["invested"] == Decimal("102.5")  # осталось 5 шт. по 20.5
    assert fifo["realized"] == Decimal("82.5")  # 145 - (42 + 20.5)
    assert fifo["fees"] == Decimal("10")
