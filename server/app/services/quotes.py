"""Биржевые котировки: получение из внешних источников и кеширование.

Акции, фонды и облигации запрашиваются у Московской биржи (ISS MOEX), цены
валют берутся из курсов Банка России, криптовалют – из открытого источника,
заданного параметром FH_CRYPTO_URL. При недоступности источника используется
последняя сохраненная цена с указанием даты ее актуальности (п. 4.2.4.4 ТЗ).
"""
import logging
import os
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import httpx
from sqlalchemy.orm import Session

from ..config import get_settings
from ..models import InvestmentTrade, Quote, QuoteHistory, utcnow
from .currency import RatesUnavailable, get_rate
from .tasks import task

log = logging.getLogger(__name__)

# Тип актива -> (рынок, режимы торгов в порядке проверки)
MOEX_BOARDS = {
    "stock": ("shares", ["TQBR"]),
    "etf": ("shares", ["TQTF"]),
    "bond": ("bonds", ["TQOB", "TQCB"]),
}
CRYPTO_IDS = {
    "BTC": "bitcoin", "ETH": "ethereum", "TON": "the-open-network", "SOL": "solana",
    "BNB": "binancecoin", "USDT": "tether", "XRP": "ripple", "DOGE": "dogecoin",
}
CRYPTO_URL = os.getenv("FH_CRYPTO_URL", "https://api.coingecko.com/api/v3")
# Цены для автономной разработки и тестов (режим FH_QUOTES_PROVIDER=static)
STATIC_QUOTES = {
    "SBER": Decimal("300"), "GAZP": Decimal("130"), "LKOH": Decimal("7000"),
    "TMOS": Decimal("7.5"), "SU26238RMFS4": Decimal("520"), "USD": Decimal("90"),
    "BTC": Decimal("9000000"),
}
_STALE_AFTER = timedelta(hours=24)


class QuoteUnavailable(Exception):
    pass


def _get_json(url: str, params: dict) -> dict:
    """GET-запрос с одной повторной попыткой при сетевой ошибке."""
    timeout = get_settings().external_timeout_seconds
    last_error: Exception | None = None
    for _ in range(2):
        try:
            response = httpx.get(url, params=params, timeout=timeout, follow_redirects=True)
            response.raise_for_status()
            return response.json()
        except Exception as exc:
            last_error = exc
    raise QuoteUnavailable(str(last_error))


def _table(block: dict) -> list[dict]:
    return [dict(zip(block["columns"], row, strict=False)) for row in block.get("data", [])]


def fetch_moex_quote(ticker: str, asset_type: str) -> tuple[Decimal, str, str]:
    """Возвращает (цена, валюта, краткое название) инструмента Московской биржи."""
    market, boards = MOEX_BOARDS[asset_type]
    base = get_settings().quotes_url
    for board in boards:
        data = _get_json(
            f"{base}/engines/stock/markets/{market}/boards/{board}/securities/{ticker}.json",
            {
                "iss.meta": "off",
                "iss.only": "marketdata,securities",
                "marketdata.columns": "SECID,LAST,LCURRENTPRICE,MARKETPRICE",
                "securities.columns": "SECID,SHORTNAME,PREVPRICE,FACEVALUE,CURRENCYID",
            },
        )
        market_rows, security_rows = _table(data["marketdata"]), _table(data["securities"])
        if not security_rows:
            continue
        security = security_rows[0]
        market_row = market_rows[0] if market_rows else {}
        price = (
            market_row.get("LAST") or market_row.get("LCURRENTPRICE")
            or market_row.get("MARKETPRICE") or security.get("PREVPRICE")
        )
        if price is None:
            continue
        price = Decimal(str(price))
        if asset_type == "bond":  # цена облигации задается в процентах от номинала
            price = price * Decimal(str(security.get("FACEVALUE") or 1000)) / 100
        currency = {"SUR": "RUB", "RUR": "RUB"}.get(security.get("CURRENCYID"), "RUB")
        return price, currency, security.get("SHORTNAME") or ticker
    raise QuoteUnavailable(f"Инструмент {ticker} не найден на Московской бирже")


def fetch_moex_history(ticker: str, asset_type: str, start: date) -> list[tuple[date, Decimal]]:
    """Исторические цены закрытия инструмента с указанной даты."""
    market, boards = MOEX_BOARDS[asset_type]
    base = get_settings().quotes_url
    for board in boards:
        result: list[tuple[date, Decimal]] = []
        offset = 0
        while True:
            data = _get_json(
                f"{base}/history/engines/stock/markets/{market}/boards/{board}"
                f"/securities/{ticker}.json",
                {
                    "iss.meta": "off", "from": start.isoformat(), "start": offset,
                    "history.columns": "TRADEDATE,CLOSE,FACEVALUE",
                },
            )
            rows = _table(data["history"])
            for row in rows:
                if row.get("CLOSE") is None:
                    continue
                price = Decimal(str(row["CLOSE"]))
                if asset_type == "bond":
                    price = price * Decimal(str(row.get("FACEVALUE") or 1000)) / 100
                result.append((date.fromisoformat(row["TRADEDATE"]), price))
            if len(rows) < 100:
                break
            offset += len(rows)
        if result:
            return result
    return []


def fetch_quote(db: Session, ticker: str, asset_type: str) -> tuple[Decimal, str, str, str]:
    """Возвращает (цена, валюта, название, источник) из внешнего источника."""
    ticker = ticker.upper()
    if get_settings().quotes_provider == "static":
        if ticker not in STATIC_QUOTES:
            raise QuoteUnavailable(f"Нет цены для {ticker}")
        return STATIC_QUOTES[ticker], "RUB", ticker, "static"
    if asset_type == "currency":
        try:
            return get_rate(db, ticker), "RUB", ticker, "cbr"
        except RatesUnavailable as exc:
            raise QuoteUnavailable(str(exc)) from exc
    if asset_type == "crypto":
        coin = CRYPTO_IDS.get(ticker, ticker.lower())
        data = _get_json(f"{CRYPTO_URL}/simple/price", {"ids": coin, "vs_currencies": "rub"})
        if coin not in data:
            raise QuoteUnavailable(f"Нет цены для {ticker}")
        return Decimal(str(data[coin]["rub"])), "RUB", ticker, "coingecko"
    price, currency, name = fetch_moex_quote(ticker, asset_type)
    return price, currency, name, "moex"


def refresh_quote(db: Session, ticker: str, asset_type: str) -> Quote | None:
    """Обновляет цену актива; при сбое источника оставляет сохраненное значение."""
    ticker = ticker.upper()
    quote = db.get(Quote, ticker)
    try:
        price, currency, _name, source = fetch_quote(db, ticker, asset_type)
    except Exception as exc:
        log.warning("Котировка %s недоступна: %s", ticker, exc)
        return quote
    if quote is None:
        quote = Quote(ticker=ticker, asset_type=asset_type, price=price, currency=currency)
        db.add(quote)
    quote.price, quote.currency, quote.source, quote.as_of = price, currency, source, utcnow()
    today = date.today()
    history = (
        db.query(QuoteHistory)
        .filter(QuoteHistory.ticker == ticker, QuoteHistory.day == today)
        .first()
    )
    if history is None:
        db.add(QuoteHistory(ticker=ticker, day=today, price=price))
    else:
        history.price = price
    db.flush()
    return quote


@task("refresh_quote")
def refresh_quote_task(db: Session, ticker: str, asset_type: str) -> None:
    """Фоновая задача: обновление цены актива после регистрации сделки."""
    refresh_quote(db, ticker, asset_type)


def user_tickers(db: Session, user_id: str | None = None) -> list[tuple[str, str]]:
    query = db.query(InvestmentTrade.ticker, InvestmentTrade.asset_type).filter(
        InvestmentTrade.deleted_at.is_(None)
    )
    if user_id:
        query = query.filter(InvestmentTrade.user_id == user_id)
    return sorted(set(query.distinct().all()))


def refresh_user_quotes(db: Session, user_id: str | None = None, *, force: bool = False) -> int:
    """Обновляет цены активов пользователя (или всех пользователей)."""
    updated = 0
    now = utcnow()
    for ticker, asset_type in user_tickers(db, user_id):
        quote = db.get(Quote, ticker.upper())
        if quote is not None and not force:
            as_of = quote.as_of if quote.as_of.tzinfo else quote.as_of.replace(tzinfo=UTC)
            if now - as_of < timedelta(minutes=5):  # защита от частых обращений к источнику
                continue
        before = quote.as_of if quote else None
        refreshed = refresh_quote(db, ticker, asset_type)
        if refreshed is not None and refreshed.as_of != before:
            updated += 1
    return updated


def ensure_history(db: Session, ticker: str, asset_type: str, start: date) -> None:
    """Догружает исторические цены актива, если их нет в кеше."""
    ticker = ticker.upper()
    if asset_type not in MOEX_BOARDS or get_settings().quotes_provider == "static":
        return
    first = (
        db.query(QuoteHistory.day)
        .filter(QuoteHistory.ticker == ticker)
        .order_by(QuoteHistory.day)
        .first()
    )
    if first is not None and first[0] <= start + timedelta(days=7):
        return
    try:
        rows = fetch_moex_history(ticker, asset_type, start)
    except Exception as exc:
        log.warning("История цен %s недоступна: %s", ticker, exc)
        return
    known = {r[0] for r in db.query(QuoteHistory.day).filter(QuoteHistory.ticker == ticker)}
    for day, price in rows:
        if day not in known:
            db.add(QuoteHistory(ticker=ticker, day=day, price=price))
    db.flush()


def is_stale(as_of: datetime | None) -> bool:
    if as_of is None:
        return True
    if as_of.tzinfo is None:
        as_of = as_of.replace(tzinfo=UTC)
    return utcnow() - as_of > _STALE_AFTER
