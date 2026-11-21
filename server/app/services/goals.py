"""Финансовые цели: прогресс, прогноз даты достижения, рекомендуемые накопления."""
import math
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, ROUND_UP, Decimal

from sqlalchemy import func
from sqlalchemy.orm import Session

from ..models import Goal, GoalContribution, User, utcnow
from ..schemas import GoalOut, GoalProgressOut
from .notifier import notify
from .periods import months_between

ZERO = Decimal("0")
CENT = Decimal("0.01")


def saved_amount(db: Session, goal: Goal) -> Decimal:
    total = (
        db.query(func.coalesce(func.sum(GoalContribution.amount), 0))
        .filter(GoalContribution.goal_id == goal.id, GoalContribution.deleted_at.is_(None))
        .scalar()
    )
    return Decimal(total)


def goal_progress(db: Session, goal: Goal, today: date | None = None) -> GoalProgressOut:
    """Рассчитывает показатели цели.

    Прогресс – доля накопленной суммы от целевой. Прогнозируемая дата достижения
    определяется по среднему темпу накоплений с даты первого взноса. Рекомендуемая
    сумма ежемесячных накоплений – остаток, деленный на число месяцев до срока.
    """
    today = today or date.today()
    target = Decimal(goal.target_amount)
    saved = saved_amount(db, goal)
    remaining = max(target - saved, ZERO)
    percent = float(min(saved / target * 100, Decimal("100"))) if target > 0 else 0.0

    months_left = months_between(today, goal.deadline) if goal.deadline > today else 1
    recommended = (remaining / months_left).quantize(CENT, ROUND_UP) if remaining > 0 else ZERO

    forecast: date | None = None
    if remaining == 0:
        forecast = (goal.achieved_at.date() if goal.achieved_at else today)
    elif saved > 0:
        first = (
            db.query(func.min(GoalContribution.occurred_at))
            .filter(GoalContribution.goal_id == goal.id, GoalContribution.deleted_at.is_(None))
            .scalar()
        )
        start = first.date() if first else goal.created_at.date()
        days = max((today - start).days, 30)  # темп оценивается не менее чем за месяц
        # Темп накоплений: saved за days дней; остаток будет накоплен за remaining * days / saved
        forecast = today + timedelta(days=math.ceil(remaining * days / saved))
    on_track = remaining == 0 or (forecast is not None and forecast <= goal.deadline)
    return GoalProgressOut(
        goal=GoalOut.model_validate(goal),
        saved=saved.quantize(CENT, ROUND_HALF_UP),
        remaining=remaining.quantize(CENT, ROUND_HALF_UP),
        percent=round(percent, 1),
        forecast_date=forecast,
        recommended_monthly=recommended,
        on_track=on_track,
        read_only=goal.status == "achieved",
    )


def refresh_goal_state(db: Session, user: User, goal: Goal) -> bool:
    """Переводит цель в состояние «достигнута» при накоплении целевой суммы.

    Достигнутая цель доступна только для просмотра (п. 4.2.1.2 ж ТЗ).
    Возвращает True, если состояние цели изменилось.
    """
    if goal.status == "achieved" or goal.deleted_at is not None:
        return False
    if saved_amount(db, goal) >= Decimal(goal.target_amount):
        goal.status = "achieved"
        goal.achieved_at = utcnow()
        notify(
            db, user, "goal_achieved", f"Цель достигнута: {goal.name}",
            f"Накоплено {goal.target_amount} {goal.currency}. Поздравляем!",
            important=True, payload={"goal_id": goal.id},
        )
        return True
    return False
