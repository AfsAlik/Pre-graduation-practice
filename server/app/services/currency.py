"""Курсы валют: получение из внешнего источника, кеширование и пересчет сумм.

Источник по умолчанию – официальные курсы Банка России. При недоступности
источника используются последние сохраненные значения, а пользователю
сообщается дата их актуальности (п. 4.2.4.4 ТЗ).
"""
import logging
from datetime import UTC, date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal
from xml.etree import ElementTree

import httpx
from sqlalchemy.orm import Session

from ..config import get_settings
from ..models import ExchangeRate, utcnow

log = logging.getLogger(__name__)

# Курсы для автономной разработки и тестов (режим FH_RATES_PROVIDER=static)
STATIC_RATES = {
    "USD": Decimal("90.00"),
    "EUR": Decimal("98.00"),
    "CNY": Decimal("12.50"),
    "KZT": Decimal("0.18"),
    "BYN": Decimal("27.50"),
    "GBP": Decimal("114.00"),
}
CENT = Decimal("0.01")


class RatesUnavailable(Exception):
    """Нет ни актуального, ни сохраненного курса валюты."""


def parse_cbr_xml(content: bytes) -> tuple[date, dict[str, Decimal]]:
    """Разбирает ответ Банка России: дата и курсы валют к рублю за единицу."""
    root = ElementTree.fromstring(content)
    day = datetime.strptime(root.attrib["Date"], "%d.%m.%Y").date()
    rates: dict[str, Decimal] = {}
    for valute in root.findall("Valute"):
        code = valute.findtext("CharCode", "").strip()
        nominal = Decimal(valute.findtext("Nominal", "1").replace(",", "."))
        value = Decimal(valute.findtext("Value", "0").replace(",", "."))
        if code and nominal > 0:
            rates[code] = (value / nominal).quantize(Decimal("0.000001"))
    return day, rates


def fetch_rates(provider: str | None = None) -> tuple[date, dict[str, Decimal], str]:
    settings = get_settings()
    if (provider or settings.rates_provider) == "static":
        return date.today(), dict(STATIC_RATES), "static"
    response = httpx.get(
        settings.rates_url, timeout=settings.external_timeout_seconds, follow_redirects=True
    )
    response.raise_for_status()
    day, rates = parse_cbr_xml(response.content)
    return day, rates, "cbr"


def refresh_rates(db: Session) -> dict:
    """Обновляет курсы валют. При сбое источника сохраненные курсы не изменяются."""
    from .settings_store import integration  # локальный импорт: модуль настроек использует БД

    try:
        day, rates, source = fetch_rates(integration(db, "rates_provider"))
    except Exception as exc:  # сеть, формат ответа и т.п.
        log.warning("Источник курсов валют недоступен: %s", exc)
        return {"ok": False, "error": str(exc), **rates_info(db)}
    existing = {
        r.currency: r for r in db.query(ExchangeRate).filter(ExchangeRate.day == day).all()
    }
    for code, value in rates.items():
        row = existing.get(code)
        if row is None:
            db.add(ExchangeRate(currency=code, day=day, rate_to_rub=value, source=source))
        else:
            row.rate_to_rub, row.fetched_at, row.source = value, utcnow(), source
    db.flush()
    return {"ok": True, "updated": len(rates), **rates_info(db)}


def rates_info(db: Session) -> dict:
    """Дата актуальности сохраненных курсов и признак их устаревания."""
    last = db.query(ExchangeRate).order_by(ExchangeRate.fetched_at.desc()).first()
    if last is None:
        return {"as_of": None, "fetched_at": None, "stale": True}
    fetched = last.fetched_at
    if fetched.tzinfo is None:
        fetched = fetched.replace(tzinfo=UTC)
    stale = utcnow() - fetched > timedelta(hours=get_settings().rates_ttl_hours)
    return {"as_of": last.day.isoformat(), "fetched_at": fetched.isoformat(), "stale": stale}


def get_rate(db: Session, currency: str, on: date | None = None) -> Decimal:
    """Курс валюты к рублю на дату (последний известный не позже этой даты)."""
    if currency == "RUB":
        return Decimal("1")
    query = db.query(ExchangeRate).filter(ExchangeRate.currency == currency)
    row = None
    if on is not None:
        row = query.filter(ExchangeRate.day <= on).order_by(ExchangeRate.day.desc()).first()
    if row is None:
        row = query.order_by(ExchangeRate.day.desc()).first()
    if row is None:
        if currency in STATIC_RATES and get_settings().rates_provider == "static":
            return STATIC_RATES[currency]
        raise RatesUnavailable(f"Нет курса валюты {currency}")
    return Decimal(row.rate_to_rub)


def convert(
    db: Session, amount: Decimal, from_currency: str, to_currency: str, on: date | None = None
) -> Decimal:
    """Пересчитывает сумму из одной валюты в другую через рубль."""
    amount = Decimal(amount)
    if from_currency == to_currency:
        return amount.quantize(CENT, ROUND_HALF_UP)
    rub = amount * get_rate(db, from_currency, on)
    return (rub / get_rate(db, to_currency, on)).quantize(CENT, ROUND_HALF_UP)


def safe_convert(
    db: Session, amount: Decimal, from_currency: str, to_currency: str, on: date | None = None
) -> Decimal:
    """Пересчет без исключения: при отсутствии курса сумма возвращается как есть."""
    try:
        return convert(db, amount, from_currency, to_currency, on)
    except RatesUnavailable:
        return Decimal(amount).quantize(CENT, ROUND_HALF_UP)


def list_currencies(db: Session) -> list[str]:
    codes = {r[0] for r in db.query(ExchangeRate.currency).distinct().all()}
    return ["RUB", *sorted(codes | set(STATIC_RATES))]
