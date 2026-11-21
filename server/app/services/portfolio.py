"""Инвестиционный портфель: позиции, стоимость и доходность (п. 4.2.1.3 ТЗ).

Позиции рассчитываются по сделкам. Себестоимость определяется методом средней
себестоимости либо методом FIFO по выбору пользователя (п. 4.3.1.1 ТЗ).
Комиссии покупки включаются в себестоимость позиции.
"""
from collections import defaultdict, deque
from datetime import UTC, date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy.orm import Session

from ..models import TRADE_LOCK_DAYS, InvestmentTrade, Quote, QuoteHistory, User, utcnow
from ..schemas import PortfolioOut, PositionOut
from .currency import safe_convert
from .quotes import is_stale

ZERO = Decimal("0")
CENT = Decimal("0.01")
PRICE = Decimal("0.00000001")
ASSET_TITLES = {
    "stock": "Акции", "bond": "Облигации", "etf": "Фонды",
    "currency": "Валюта", "crypto": "Криптовалюты",
}


def is_locked(trade: InvestmentTrade, now: datetime | None = None) -> bool:
    """Количество и цену сделки нельзя менять спустя 7 дней после фиксации."""
    created = trade.created_at
    if created.tzinfo is None:
        created = created.replace(tzinfo=UTC)
    return (now or utcnow()) - created > timedelta(days=TRADE_LOCK_DAYS)


def calculate_position(trades: list[InvestmentTrade], method: str = "average") -> dict:
    """Рассчитывает позицию по одному активу.

    Возвращает количество, себестоимость остатка (invested), среднюю цену,
    сумму комиссий и реализованный финансовый результат по продажам.
    """
    quantity, cost, fees, realized = ZERO, ZERO, ZERO, ZERO
    lots: deque[list[Decimal]] = deque()  # [количество, цена единицы с комиссией]
    for trade in sorted(trades, key=lambda t: (t.traded_at, t.created_at)):
        qty, price, fee = Decimal(trade.quantity), Decimal(trade.price), Decimal(trade.fee)
        fees += fee
        if trade.side == "buy":
            unit_cost = (qty * price + fee) / qty
            lots.append([qty, unit_cost])
            quantity += qty
            cost += qty * price + fee
            continue
        sell_qty = min(qty, quantity)
        if sell_qty <= 0:
            continue
        proceeds = sell_qty * price - fee
        if method == "fifo":
            sold_cost, left = ZERO, sell_qty
            while left > 0 and lots:
                lot = lots[0]
                take = min(lot[0], left)
                sold_cost += take * lot[1]
                lot[0] -= take
                left -= take
                if lot[0] == 0:
                    lots.popleft()
        else:
            sold_cost = cost / quantity * sell_qty
        realized += proceeds - sold_cost
        cost -= sold_cost
        quantity -= sell_qty
    average = (cost / quantity) if quantity > 0 else ZERO
    return {
        "quantity": quantity, "invested": cost, "average_price": average,
        "fees": fees, "realized": realized,
    }


def build_portfolio(db: Session, user: User) -> PortfolioOut:
    trades = (
        db.query(InvestmentTrade)
        .filter(InvestmentTrade.user_id == user.id, InvestmentTrade.deleted_at.is_(None))
        .all()
    )
    grouped: dict[str, list[InvestmentTrade]] = defaultdict(list)
    for trade in trades:
        grouped[trade.ticker.upper()].append(trade)
    quotes = {q.ticker: q for q in db.query(Quote).filter(Quote.ticker.in_(grouped.keys()))}

    positions: list[PositionOut] = []
    total_invested = total_value = ZERO
    by_type: dict[str, Decimal] = defaultdict(lambda: ZERO)
    oldest_quote: datetime | None = None
    for ticker, items in sorted(grouped.items()):
        state = calculate_position(items, user.costing_method)
        if state["quantity"] <= 0 and state["realized"] == 0:
            continue
        last = max(items, key=lambda t: t.traded_at)
        currency = last.currency
        quote = quotes.get(ticker)
        price = Decimal(quote.price) if quote else None
        if quote is not None:
            if quote.currency != currency:
                price = safe_convert(db, price, quote.currency, currency)
            if oldest_quote is None or quote.as_of < oldest_quote:
                oldest_quote = quote.as_of
        value = state["quantity"] * (price if price is not None else state["average_price"])
        profit = value - state["invested"]
        percent = float(profit / state["invested"] * 100) if state["invested"] > 0 else 0.0
        value_base = safe_convert(db, value, currency, user.base_currency)
        invested_base = safe_convert(db, state["invested"], currency, user.base_currency)
        total_value += value_base
        total_invested += invested_base
        by_type[last.asset_type] += value_base
        positions.append(
            PositionOut(
                ticker=ticker, name=last.name or ticker, asset_type=last.asset_type,
                currency=currency,
                quantity=state["quantity"].normalize() if state["quantity"] else ZERO,
                average_price=state["average_price"].quantize(PRICE, ROUND_HALF_UP),
                invested=state["invested"].quantize(CENT, ROUND_HALF_UP),
                fees=state["fees"].quantize(CENT, ROUND_HALF_UP),
                current_price=price, price_as_of=quote.as_of if quote else None,
                current_value=value.quantize(CENT, ROUND_HALF_UP),
                current_value_base=value_base,
                profit=profit.quantize(CENT, ROUND_HALF_UP),
                profit_percent=round(percent, 2),
                realized_profit=state["realized"].quantize(CENT, ROUND_HALF_UP),
            )
        )
    total_profit = total_value - total_invested
    structure = {
        kind: round(float(value / total_value * 100), 1) if total_value > 0 else 0.0
        for kind, value in by_type.items()
    }
    return PortfolioOut(
        base_currency=user.base_currency,
        costing_method=user.costing_method,
        positions=positions,
        total_invested_base=total_invested.quantize(CENT, ROUND_HALF_UP),
        total_value_base=total_value.quantize(CENT, ROUND_HALF_UP),
        total_profit_base=total_profit.quantize(CENT, ROUND_HALF_UP),
        total_profit_percent=(
            round(float(total_profit / total_invested * 100), 2) if total_invested > 0 else 0.0
        ),
        structure=structure,
        quotes_as_of=oldest_quote,
        quotes_stale=bool(positions) and is_stale(oldest_quote),
    )


def portfolio_dynamics(db: Session, user: User, period: str = "month") -> list[dict]:
    """Динамика стоимости портфеля за день, месяц или год по сохраненным ценам."""
    days = {"day": 1, "month": 30, "year": 365}.get(period, 30)
    today = date.today()
    start = today - timedelta(days=days)
    trades = (
        db.query(InvestmentTrade)
        .filter(InvestmentTrade.user_id == user.id, InvestmentTrade.deleted_at.is_(None))
        .order_by(InvestmentTrade.traded_at)
        .all()
    )
    if not trades:
        return []
    tickers = {t.ticker.upper() for t in trades}
    history: dict[str, list[tuple[date, Decimal]]] = defaultdict(list)
    for row in (
        db.query(QuoteHistory)
        .filter(QuoteHistory.ticker.in_(tickers))
        .order_by(QuoteHistory.day)
    ):
        history[row.ticker].append((row.day, Decimal(row.price)))
    currencies = {t.ticker.upper(): t.currency for t in trades}

    step = max(days // 60, 1)
    days_axis = [start + timedelta(days=offset) for offset in range(0, days + 1, step)]
    if days_axis[-1] != today:
        days_axis.append(today)  # последняя точка всегда соответствует текущей дате
    points: list[dict] = []
    for day in days_axis:
        total = ZERO
        for ticker in tickers:
            quantity = ZERO
            last_trade_price = ZERO
            for trade in trades:
                if trade.ticker.upper() != ticker or trade.traded_at.date() > day:
                    continue
                qty = Decimal(trade.quantity)
                quantity += qty if trade.side == "buy" else -qty
                last_trade_price = Decimal(trade.price)
            if quantity <= 0:
                continue
            price = last_trade_price  # до появления котировки – цена последней сделки
            for hist_day, hist_price in history.get(ticker, []):
                if hist_day <= day:
                    price = hist_price
                else:
                    break
            total += safe_convert(db, quantity * price, currencies[ticker], user.base_currency)
        points.append({"date": day.isoformat(), "value": str(total.quantize(CENT))})
    return points


def asset_details(db: Session, user: User, ticker: str) -> dict:
    """Детальная аналитика по активу: история цен и доходность за периоды."""
    ticker = ticker.upper()
    trades = (
        db.query(InvestmentTrade)
        .filter(
            InvestmentTrade.user_id == user.id, InvestmentTrade.deleted_at.is_(None),
            InvestmentTrade.ticker == ticker,
        )
        .order_by(InvestmentTrade.traded_at)
        .all()
    )
    history = (
        db.query(QuoteHistory).filter(QuoteHistory.ticker == ticker).order_by(QuoteHistory.day).all()
    )
    quote = db.get(Quote, ticker)
    current = Decimal(quote.price) if quote else (Decimal(history[-1].price) if history else None)
    returns: dict[str, float | None] = {}
    for label, days in (("week", 7), ("month", 30), ("quarter", 90), ("year", 365)):
        target = date.today() - timedelta(days=days)
        past = next((Decimal(h.price) for h in reversed(history) if h.day <= target), None)
        returns[label] = (
            round(float((current - past) / past * 100), 2) if current and past else None
        )
    state = calculate_position(trades, user.costing_method) if trades else None
    return {
        "ticker": ticker,
        "name": trades[-1].name if trades else ticker,
        "asset_type": trades[-1].asset_type if trades else (quote.asset_type if quote else ""),
        "current_price": str(current) if current is not None else None,
        "price_as_of": quote.as_of.isoformat() if quote else None,
        "history": [{"date": h.day.isoformat(), "price": str(h.price)} for h in history],
        "returns": returns,
        "fees": str(state["fees"].quantize(CENT)) if state else "0.00",
        "quantity": str(state["quantity"].normalize()) if state else "0",
        "average_price": str(state["average_price"].quantize(PRICE)) if state else "0",
        "realized_profit": str(state["realized"].quantize(CENT)) if state else "0.00",
    }
