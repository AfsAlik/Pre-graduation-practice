"""Аналитика и отчетность: дашборд, отчеты, прогноз остатка (п. 4.2.1.4 ТЗ)."""
import calendar
from collections import defaultdict
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy.orm import Session

from ..models import Goal, Transaction, User
from .balances import total_balance
from .budget import budgets_overview
from .categories import category_map, root_of
from .currency import rates_info, safe_convert
from .goals import goal_progress
from .periods import add_months, day_end, day_start, month_start, period_bounds
from .portfolio import build_portfolio

ZERO = Decimal("0")
CENT = Decimal("0.01")


def _q(value: Decimal) -> Decimal:
    return Decimal(value).quantize(CENT, ROUND_HALF_UP)


def load_transactions(
    db: Session, user: User, start: date, end: date, *,
    account_ids: list[str] | None = None, category_ids: list[str] | None = None,
) -> list[Transaction]:
    query = db.query(Transaction).filter(
        Transaction.user_id == user.id,
        Transaction.deleted_at.is_(None),
        Transaction.occurred_at >= day_start(start),
        Transaction.occurred_at <= day_end(end),
    )
    if account_ids:
        query = query.filter(Transaction.account_id.in_(account_ids))
    if category_ids:
        query = query.filter(Transaction.category_id.in_(category_ids))
    return query.order_by(Transaction.occurred_at).all()


def in_base(db: Session, user: User, transaction: Transaction) -> Decimal:
    return safe_convert(
        db, transaction.amount, transaction.currency, user.base_currency,
        transaction.occurred_at.date(),
    )


def totals(db: Session, user: User, items: list[Transaction]) -> tuple[Decimal, Decimal]:
    income = expense = ZERO
    for item in items:
        if item.kind == "income":
            income += in_base(db, user, item)
        elif item.kind == "expense":
            expense += in_base(db, user, item)
    return income, expense


def by_category(db: Session, user: User, items: list[Transaction], kind: str = "expense") -> list[dict]:
    """Суммы по категориям верхнего уровня с долями, по убыванию суммы."""
    categories = category_map(db, user.id)
    sums: dict[str, Decimal] = defaultdict(lambda: ZERO)
    names: dict[str, tuple[str, str]] = {}
    for item in items:
        if item.kind != kind:
            continue
        root = root_of(categories, item.category_id)
        key = root.id if root else ""
        names[key] = (root.name, root.color) if root else ("Без категории", "#9E9E9E")
        sums[key] += in_base(db, user, item)
    total = sum(sums.values(), ZERO)
    result = [
        {
            "category_id": key or None, "name": names[key][0], "color": names[key][1],
            "amount": str(_q(value)),
            "share": round(float(value / total * 100), 1) if total > 0 else 0.0,
        }
        for key, value in sums.items()
    ]
    return sorted(result, key=lambda r: Decimal(r["amount"]), reverse=True)


def daily_series(db: Session, user: User, items: list[Transaction], start: date, end: date) -> list[dict]:
    income: dict[date, Decimal] = defaultdict(lambda: ZERO)
    expense: dict[date, Decimal] = defaultdict(lambda: ZERO)
    for item in items:
        day = item.occurred_at.date()
        if item.kind == "income":
            income[day] += in_base(db, user, item)
        elif item.kind == "expense":
            expense[day] += in_base(db, user, item)
    series, day = [], start
    while day <= end:
        series.append({"date": day.isoformat(), "income": str(_q(income[day])),
                       "expense": str(_q(expense[day]))})
        day += timedelta(days=1)
    return series


def monthly_series(db: Session, user: User, months: int = 12, today: date | None = None) -> list[dict]:
    """Доходы и расходы по месяцам за последние months месяцев (включая текущий)."""
    today = today or date.today()
    first = add_months(month_start(today), -(months - 1))
    items = load_transactions(db, user, first, today)
    income: dict[str, Decimal] = defaultdict(lambda: ZERO)
    expense: dict[str, Decimal] = defaultdict(lambda: ZERO)
    for item in items:
        key = item.occurred_at.strftime("%Y-%m")
        if item.kind == "income":
            income[key] += in_base(db, user, item)
        elif item.kind == "expense":
            expense[key] += in_base(db, user, item)
    result = []
    for index in range(months):
        key = add_months(first, index).strftime("%Y-%m")
        result.append({"month": key, "income": str(_q(income[key])), "expense": str(_q(expense[key]))})
    return result


def forecast_month_end_balance(db: Session, user: User, today: date | None = None) -> dict:
    """Прогноз остатка средств на конец текущего месяца.

    Остаток = текущий баланс + ожидаемые доходы − ожидаемые расходы за оставшиеся
    дни месяца, где ожидаемые значения оцениваются по среднедневным оборотам
    за предыдущие 90 дней.
    """
    today = today or date.today()
    days_left = calendar.monthrange(today.year, today.month)[1] - today.day
    history = load_transactions(db, user, today - timedelta(days=90), today - timedelta(days=1))
    income, expense = totals(db, user, history)
    span = Decimal(90)
    if history:
        span = Decimal(max((today - history[0].occurred_at.date()).days, 1))
    balance = total_balance(db, user, liquid_only=True)
    expected_income = income / span * days_left
    expected_expense = expense / span * days_left
    return {
        "current_balance": str(_q(balance)),
        "expected_income": str(_q(expected_income)),
        "expected_expense": str(_q(expected_expense)),
        "forecast_balance": str(_q(balance + expected_income - expected_expense)),
        "days_left": days_left,
        "currency": user.base_currency,
    }


def dashboard(db: Session, user: User, today: date | None = None) -> dict:
    """Данные главного экрана мобильного приложения."""
    today = today or date.today()
    start, end = period_bounds("month", today)
    items = load_transactions(db, user, start, end)
    income, expense = totals(db, user, items)
    budgets = budgets_overview(db, user, today)
    goals = (
        db.query(Goal)
        .filter(Goal.user_id == user.id, Goal.deleted_at.is_(None), Goal.status == "active")
        .all()
    )
    priority_rank = {"high": 0, "medium": 1, "low": 2}
    goals.sort(key=lambda g: (priority_rank.get(g.priority, 1), g.deadline))
    portfolio = build_portfolio(db, user)
    limit_total = sum((b.budget.limit_amount for b in budgets), ZERO)
    spent_total = sum((b.spent for b in budgets), ZERO)
    return {
        "currency": user.base_currency,
        "period_start": start.isoformat(),
        "period_end": end.isoformat(),
        "total_balance": str(_q(total_balance(db, user))),
        "income": str(_q(income)),
        "expense": str(_q(expense)),
        "expense_chart": daily_series(db, user, items, start, min(end, today)),
        "expenses_by_category": by_category(db, user, items)[:6],
        "budget": {
            "limit": str(_q(limit_total)), "spent": str(_q(spent_total)),
            "percent": round(float(spent_total / limit_total * 100), 1) if limit_total > 0 else 0.0,
            "exceeded": sum(1 for b in budgets if b.status != "ok"),
        },
        "goal": goal_progress(db, goals[0], today).model_dump(mode="json") if goals else None,
        "portfolio": {
            "value": str(portfolio.total_value_base),
            "profit": str(portfolio.total_profit_base),
            "profit_percent": portfolio.total_profit_percent,
            "positions": len(portfolio.positions),
        },
        "forecast": forecast_month_end_balance(db, user, today),
        "rates": rates_info(db),
    }


def summary_text(income: Decimal, expense: Decimal, categories: list[dict], currency: str) -> str:
    """Текстовая сводка отчета."""
    parts = [
        f"Доходы за период составили {_q(income)} {currency}, расходы – {_q(expense)} {currency}."
    ]
    balance = income - expense
    if balance >= 0:
        parts.append(f"Доходы превысили расходы на {_q(balance)} {currency}.")
    else:
        parts.append(f"Расходы превысили доходы на {_q(-balance)} {currency}.")
    if categories:
        top = categories[0]
        parts.append(f"Наибольшая статья расходов – «{top['name']}»: {top['amount']} {currency} "
                     f"({top['share']} % всех расходов).")
    return " ".join(parts)


def build_report(
    db: Session, user: User, start: date, end: date, *,
    account_ids: list[str] | None = None, category_ids: list[str] | None = None,
) -> dict:
    """Аналитический отчет за период с фильтрацией по счетам и категориям."""
    items = load_transactions(db, user, start, end, account_ids=account_ids,
                              category_ids=category_ids)
    income, expense = totals(db, user, items)
    expenses = by_category(db, user, items, "expense")
    return {
        "currency": user.base_currency,
        "period_start": start.isoformat(),
        "period_end": end.isoformat(),
        "income": str(_q(income)),
        "expense": str(_q(expense)),
        "balance": str(_q(income - expense)),
        "operations": len(items),
        "expenses_by_category": expenses,
        "income_by_category": by_category(db, user, items, "income"),
        "daily": daily_series(db, user, items, start, end) if (end - start).days <= 92 else [],
        "monthly": monthly_series(db, user, 12, end),
        "budgets": [b.model_dump(mode="json") for b in budgets_overview(db, user, end)],
        "summary": summary_text(income, expense, expenses, user.base_currency),
    }
