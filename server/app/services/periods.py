"""Границы отчетных и бюджетных периодов."""
import calendar
from datetime import UTC, date, datetime, time, timedelta


def period_bounds(period: str, today: date | None = None) -> tuple[date, date]:
    """Первый и последний день текущей недели, месяца или года."""
    today = today or date.today()
    if period == "week":
        start = today - timedelta(days=today.weekday())
        return start, start + timedelta(days=6)
    if period == "year":
        return date(today.year, 1, 1), date(today.year, 12, 31)
    last_day = calendar.monthrange(today.year, today.month)[1]
    return date(today.year, today.month, 1), date(today.year, today.month, last_day)


def period_key(period: str, today: date | None = None) -> str:
    start, _ = period_bounds(period, today)
    return f"{period}:{start.isoformat()}"


def day_start(day: date) -> datetime:
    return datetime.combine(day, time.min, tzinfo=UTC)


def day_end(day: date) -> datetime:
    return datetime.combine(day, time.max, tzinfo=UTC)


def add_months(day: date, months: int) -> date:
    month_index = day.month - 1 + months
    year, month = day.year + month_index // 12, month_index % 12 + 1
    return date(year, month, min(day.day, calendar.monthrange(year, month)[1]))


def month_start(day: date) -> date:
    return date(day.year, day.month, 1)


def months_between(start: date, end: date) -> int:
    """Число полных и неполных месяцев от start до end (не менее 1)."""
    months = (end.year - start.year) * 12 + end.month - start.month
    if end.day > start.day:
        months += 1
    return max(months, 1)
