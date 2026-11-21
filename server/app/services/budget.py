"""Бюджетирование: контроль исполнения лимитов и уведомления (п. 4.2.1.2 ТЗ)."""
from datetime import date
from decimal import Decimal

from sqlalchemy.orm import Session

from ..models import Budget, Transaction, User
from ..schemas import BudgetOut, BudgetStatusOut
from .categories import category_map, root_of, subtree_ids
from .currency import safe_convert
from .notifier import notify, user_settings
from .periods import day_end, day_start, period_bounds, period_key

ZERO = Decimal("0")
CRITICAL_PERCENT = 120.0
STATUS_TITLES = {
    "ok": "в пределах лимита",
    "exceeded": "превышение",
    "critical": "критическое превышение",
}


def spent_in_period(
    db: Session, user: User, category_ids: set[str], start: date, end: date, currency: str
) -> Decimal:
    """Сумма расходов по категориям за период в указанной валюте."""
    rows = (
        db.query(Transaction)
        .filter(
            Transaction.user_id == user.id,
            Transaction.deleted_at.is_(None),
            Transaction.kind == "expense",
            Transaction.category_id.in_(category_ids),
            Transaction.occurred_at >= day_start(start),
            Transaction.occurred_at <= day_end(end),
        )
        .all()
    )
    total = ZERO
    for row in rows:
        total += safe_convert(db, row.amount, row.currency, currency, row.occurred_at.date())
    return total


def classify(percent: float) -> str:
    if percent > CRITICAL_PERCENT:
        return "critical"
    if percent > 100.0:
        return "exceeded"
    return "ok"


def budget_status(db: Session, user: User, budget: Budget, today: date | None = None) -> BudgetStatusOut:
    categories = category_map(db, user.id)
    start, end = period_bounds(budget.period, today)
    spent = spent_in_period(
        db, user, subtree_ids(categories, budget.category_id), start, end, budget.currency
    )
    limit = Decimal(budget.limit_amount)
    percent = float(spent / limit * 100) if limit > 0 else 0.0
    status = classify(percent)
    category = categories.get(budget.category_id)
    return BudgetStatusOut(
        budget=BudgetOut.model_validate(budget),
        category_name=category.name if category else "",
        period_start=start,
        period_end=end,
        spent=spent,
        remaining=limit - spent,
        percent=round(percent, 1),
        status=status,
        status_title=STATUS_TITLES[status],
        warning=percent >= user_settings(user).budget_threshold_percent,
    )


def budgets_overview(db: Session, user: User, today: date | None = None) -> list[BudgetStatusOut]:
    budgets = (
        db.query(Budget)
        .filter(Budget.user_id == user.id, Budget.deleted_at.is_(None), Budget.is_active.is_(True))
        .order_by(Budget.created_at)
        .all()
    )
    return [budget_status(db, user, b, today) for b in budgets]


def check_budgets_for_category(db: Session, user: User, category_id: str | None) -> None:
    """Проверяет лимиты после сохранения расхода и формирует уведомления.

    Уведомление о приближении к лимиту формируется при достижении порога
    (по умолчанию 80 %), уведомление о превышении – при расходе сверх лимита.
    Для каждого бюджетного периода каждое уведомление формируется один раз.
    """
    if not category_id:
        return
    categories = category_map(db, user.id)
    chain: set[str] = set()
    current = categories.get(category_id)
    while current is not None and current.id not in chain:
        chain.add(current.id)
        current = categories.get(current.parent_id) if current.parent_id else None
    budgets = (
        db.query(Budget)
        .filter(
            Budget.user_id == user.id, Budget.deleted_at.is_(None),
            Budget.is_active.is_(True), Budget.category_id.in_(chain),
        )
        .all()
    )
    threshold = user_settings(user).budget_threshold_percent
    for budget in budgets:
        state = budget_status(db, user, budget)
        key = period_key(budget.period)
        payload = {"budget_id": budget.id, "category_id": budget.category_id}
        if state.percent > 100 and budget.exceeded_period != key:
            budget.exceeded_period = key
            notify(
                db, user, "budget_exceeded",
                f"Превышен бюджет: {state.category_name}",
                f"Израсходовано {state.spent} из {budget.limit_amount} {budget.currency} "
                f"({state.percent:.0f} %).",
                important=True, payload=payload,
            )
        elif threshold <= state.percent <= 100 and budget.warned_period != key:
            budget.warned_period = key
            notify(
                db, user, "budget_warning",
                f"Бюджет почти исчерпан: {state.category_name}",
                f"Израсходовано {state.percent:.0f} % лимита, осталось "
                f"{state.remaining} {budget.currency}.",
                payload=payload,
            )


def budget_root_category(db: Session, user: User, category_id: str | None):
    return root_of(category_map(db, user.id), category_id)
