"""Интеллектуальные рекомендации: паттерны, прогнозы, риски (п. 4.2.1.5 ТЗ)."""
import statistics
from collections import defaultdict
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy.orm import Session

from ..ml import client
from ..ml.categorizer import normalize
from ..models import Account, Budget, Goal, Recommendation, RiskAlert, User, utcnow
from .balances import account_balances, total_balance
from .categories import category_map, find_by_name, root_of
from .currency import safe_convert
from .goals import goal_progress
from .notifier import notify
from .periods import add_months, month_start
from .reports import in_base, load_transactions, monthly_series, totals

ZERO = Decimal("0")
CENT = Decimal("0.01")
DEBT_LOAD_LIMIT = 0.40  # допустимая доля кредитных платежей в доходе
RESERVE_MONTHS = 3  # достаточный резервный фонд, месяцев расходов
HISTORY_MONTHS = 6


def _q(value) -> Decimal:
    return Decimal(value).quantize(CENT, ROUND_HALF_UP)


# ------------------------------------------------------------------ паттерны
def analyze_patterns(db: Session, user: User, today: date | None = None) -> dict:
    """Выявляет регулярные расходы, сезонные колебания и аномальные операции."""
    today = today or date.today()
    start = add_months(month_start(today), -12)
    items = [t for t in load_transactions(db, user, start, today) if t.kind == "expense"]
    categories = category_map(db, user.id)

    # Регулярные расходы: один получатель встречается не менее чем в трех месяцах
    by_merchant: dict[str, list] = defaultdict(list)
    for item in items:
        key = normalize(item.merchant or item.comment)
        if len(key) >= 3:
            by_merchant[key].append(item)
    regular = []
    for key, group in by_merchant.items():
        months = {t.occurred_at.strftime("%Y-%m") for t in group}
        if len(months) >= 3:
            amounts = [float(in_base(db, user, t)) for t in group]
            regular.append({
                "name": group[-1].merchant or group[-1].comment or key,
                "months": len(months),
                "average_amount": round(statistics.mean(amounts), 2),
                "monthly_amount": round(sum(amounts) / len(months), 2),
                "operations": len(group),
            })
    regular.sort(key=lambda r: r["monthly_amount"], reverse=True)

    # Аномальные операции: сумма существенно выше типичной для категории. Используются
    # медиана и медианное абсолютное отклонение: в отличие от среднего и стандартного
    # отклонения они не искажаются самой аномальной операцией.
    by_category: dict[str, list] = defaultdict(list)
    for item in items:
        root = root_of(categories, item.category_id)
        by_category[root.id if root else ""].append(item)
    anomalies = []
    for key, group in by_category.items():
        if len(group) < 5:
            continue
        amounts = [float(in_base(db, user, t)) for t in group]
        median = statistics.median(amounts)
        deviation = statistics.median(abs(a - median) for a in amounts) * 1.4826
        threshold = median + 3 * deviation if deviation > 0 else median * 3
        for item, amount in zip(group, amounts, strict=True):
            if amount > max(threshold, median * 2) and (today - item.occurred_at.date()).days <= 31:
                anomalies.append({
                    "transaction_id": item.id, "date": item.occurred_at.date().isoformat(),
                    "amount": round(amount, 2), "category_typical": round(median, 2),
                    "category": categories[key].name if key in categories else "Без категории",
                    "description": item.merchant or item.comment,
                })

    # Сезонные колебания: отклонение расходов месяца от среднемесячных
    monthly: dict[str, Decimal] = defaultdict(lambda: ZERO)
    for item in items:
        monthly[item.occurred_at.strftime("%Y-%m")] += in_base(db, user, item)
    seasonal = []
    if len(monthly) >= 6:
        average = float(sum(monthly.values(), ZERO) / len(monthly))
        for month, value in sorted(monthly.items()):
            ratio = float(value) / average if average else 0.0
            if abs(ratio - 1) >= 0.25:
                seasonal.append({"month": month, "amount": float(_q(value)),
                                 "deviation_percent": round((ratio - 1) * 100, 1)})
    return {"regular": regular[:10], "anomalies": anomalies[:10], "seasonal": seasonal}


# ------------------------------------------------------------------ прогноз
def forecast(db: Session, user: User, today: date | None = None) -> dict:
    """Прогноз расходов и доходов на следующий месяц с интервалом уверенности."""
    today = today or date.today()
    series = monthly_series(db, user, 36, today)[:-1]  # без текущего неполного месяца
    while series and Decimal(series[0]["income"]) == 0 and Decimal(series[0]["expense"]) == 0:
        series.pop(0)
    result = {"month": add_months(month_start(today), 1).strftime("%Y-%m"),
              "currency": user.base_currency, "confidence": 0.95}
    for key in ("expense", "income"):
        result[key] = client.forecast_next([float(row[key]) for row in series])
    return result


# ------------------------------------------------------------------ риски
def assess_risks(db: Session, user: User, today: date | None = None) -> list[RiskAlert]:
    """Оценивает финансовые риски и формирует предупреждения.

    Контролируются доля кредитных платежей в доходе (порог 40 %) и достаточность
    резервного фонда (ликвидные средства не менее трех среднемесячных расходов).
    """
    today = today or date.today()
    start = today - timedelta(days=90)
    items = load_transactions(db, user, start, today)
    income, expense = totals(db, user, items)
    categories = category_map(db, user.id)
    credit_root = find_by_name(db, user.id, "Кредиты", "expense")
    credit_accounts = {
        a.id for a in db.query(Account).filter(Account.user_id == user.id, Account.type == "credit")
    }
    credit_payments = ZERO
    for item in items:
        root = root_of(categories, item.category_id)
        if item.kind == "expense" and credit_root is not None and root is not None \
                and root.id == credit_root.id:
            credit_payments += in_base(db, user, item)
        elif item.kind == "transfer" and item.to_account_id in credit_accounts:
            credit_payments += in_base(db, user, item)

    found: dict[str, tuple[float, str, str]] = {}
    if income > 0 and credit_payments / income > Decimal(str(DEBT_LOAD_LIMIT)):
        share = float(credit_payments / income)
        found["debt_load"] = (
            share, "critical" if share > 0.6 else "warning",
            f"Платежи по кредитам составляют {share * 100:.0f} % дохода при рекомендуемом "
            f"уровне не более {DEBT_LOAD_LIMIT * 100:.0f} %. Рассмотрите рефинансирование "
            "или досрочное погашение наиболее дорогих кредитов.",
        )
    monthly_expense = expense / 3
    reserve = total_balance(db, user, liquid_only=True)
    if monthly_expense > 0 and reserve < monthly_expense * RESERVE_MONTHS:
        months = float(reserve / monthly_expense) if reserve > 0 else 0.0
        found["reserve_fund"] = (
            months, "critical" if months < 1 else "warning",
            f"Резервный фонд покрывает {months:.1f} мес. расходов при рекомендуемом уровне "
            f"не менее {RESERVE_MONTHS} мес. Рекомендуется откладывать часть дохода "
            "на отдельный счет.",
        )

    active = {
        a.kind: a for a in db.query(RiskAlert).filter(
            RiskAlert.user_id == user.id, RiskAlert.resolved_at.is_(None))
    }
    for kind, alert in active.items():
        if kind not in found:
            alert.resolved_at = utcnow()
    result = []
    for kind, (value, severity, message) in found.items():
        alert = active.get(kind)
        if alert is None:
            alert = RiskAlert(user_id=user.id, kind=kind, severity=severity, message=message,
                              value=value)
            db.add(alert)
            notify(db, user, "risk", "Выявлен финансовый риск", message, important=True,
                   payload={"risk": kind})
        else:
            alert.severity, alert.message, alert.value = severity, message, value
        result.append(alert)
    db.flush()
    return result


# ------------------------------------------------------------------ рекомендации
def _add(db: Session, user: User, existing: dict, *, kind: str, key: str, title: str, text: str,
         explanation: str, saving: Decimal, action: dict | None = None) -> Recommendation | None:
    """Добавляет рекомендацию, если такой же активной рекомендации еще нет."""
    if key in existing:
        return None
    recommendation = Recommendation(
        user_id=user.id, kind=kind, dedup_key=key, title=title, text=text,
        explanation=explanation, estimated_saving=_q(max(saving, ZERO)), action=action or {},
    )
    db.add(recommendation)
    existing[key] = recommendation
    return recommendation


def generate_recommendations(db: Session, user: User, today: date | None = None) -> list[Recommendation]:
    """Формирует персонализированные рекомендации на основе правил и паттернов.

    Каждая рекомендация содержит пояснение (на каких данных она основана) и
    оценку возможной экономии в месяц.
    """
    today = today or date.today()
    month_key = today.strftime("%Y-%m")
    existing = {
        r.dedup_key: r for r in db.query(Recommendation).filter(
            Recommendation.user_id == user.id,
            Recommendation.created_at >= utcnow() - timedelta(days=45))
    }
    created: list[Recommendation] = []
    categories = category_map(db, user.id)
    currency = user.base_currency

    # Расходы по категориям за предыдущие полные месяцы
    first = add_months(month_start(today), -HISTORY_MONTHS)
    history = [t for t in load_transactions(db, user, first, month_start(today) - timedelta(days=1))
               if t.kind == "expense"]
    current = [t for t in load_transactions(db, user, month_start(today), today)
               if t.kind == "expense"]
    months_with_data = len({t.occurred_at.strftime("%Y-%m") for t in history}) or 1
    average: dict[str, Decimal] = defaultdict(lambda: ZERO)
    for item in history:
        root = root_of(categories, item.category_id)
        if root is not None:
            average[root.id] += in_base(db, user, item) / months_with_data
    spent_now: dict[str, Decimal] = defaultdict(lambda: ZERO)
    for item in current:
        root = root_of(categories, item.category_id)
        if root is not None:
            spent_now[root.id] += in_base(db, user, item)
    budgeted = {
        b.category_id for b in db.query(Budget).filter(
            Budget.user_id == user.id, Budget.deleted_at.is_(None), Budget.is_active.is_(True))
    }
    essential = {"Жилье", "Кредиты", "Здоровье", "Связь"}

    # 1. Крупные категории без бюджетного лимита
    ranked = sorted(average.items(), key=lambda kv: kv[1], reverse=True)
    for category_id, value in ranked[:5]:
        category = categories[category_id]
        if category_id in budgeted or category.name in essential or value < 1000:
            continue
        limit = _q(value * Decimal("0.9"))
        item = _add(
            db, user, existing, kind="set_budget", key=f"budget:{category_id}:{month_key}",
            title=f"Установите бюджет на «{category.name}»",
            text=f"В среднем вы тратите на «{category.name}» {_q(value)} {currency} в месяц. "
                 f"Лимит {limit} {currency} поможет сократить эти расходы на 10 %.",
            explanation=f"Рассчитано по вашим расходам за последние {months_with_data} мес.; "
                        "по этой категории лимит сейчас не установлен.",
            saving=value - limit,
            action={"type": "set_budget", "category_id": category_id,
                    "limit_amount": str(limit), "period": "month", "label": "Установить бюджет"},
        )
        if item:
            created.append(item)

    # 2. Рост расходов по категории в текущем месяце
    for category_id, value in spent_now.items():
        base = average.get(category_id, ZERO)
        if base >= 1000 and value > base * Decimal("1.2"):
            category = categories[category_id]
            item = _add(
                db, user, existing, kind="spending_growth",
                key=f"growth:{category_id}:{month_key}",
                title=f"Расходы на «{category.name}» выше обычного",
                text=f"В этом месяце на «{category.name}» уже потрачено {_q(value)} {currency} – "
                     f"на {float((value / base - 1) * 100):.0f} % больше среднемесячного уровня "
                     f"({_q(base)} {currency}).",
                explanation="Сравнение расходов текущего месяца со средним значением "
                            f"за {months_with_data} предыдущих мес.",
                saving=value - base,
            )
            if item:
                created.append(item)

    # 3. Регулярные необязательные платежи (подписки)
    patterns = analyze_patterns(db, user, today)
    entertainment = find_by_name(db, user.id, "Развлечения", "expense")
    subscriptions = [r for r in patterns["regular"] if r["monthly_amount"] <= 2000]
    if len(subscriptions) >= 2 and entertainment is not None:
        total = Decimal(str(sum(r["monthly_amount"] for r in subscriptions)))
        names = ", ".join(r["name"] for r in subscriptions[:4])
        item = _add(
            db, user, existing, kind="subscriptions", key=f"subscriptions:{month_key}",
            title="Проверьте регулярные платежи",
            text=f"На регулярные небольшие платежи ({names}) уходит около {_q(total)} {currency} "
                 "в месяц. Отказ от неиспользуемых подписок сократит расходы.",
            explanation=f"Обнаружено {len(subscriptions)} получателей, которым вы платите "
                        "ежемесячно не менее трех месяцев подряд.",
            saving=total * Decimal("0.3"),
        )
        if item:
            created.append(item)

    # 4. Отставание от графика накоплений по цели
    goals = db.query(Goal).filter(
        Goal.user_id == user.id, Goal.deleted_at.is_(None), Goal.status == "active").all()
    for goal in goals:
        progress = goal_progress(db, goal, today)
        if not progress.on_track and progress.recommended_monthly > 0:
            item = _add(
                db, user, existing, kind="goal_pace", key=f"goal:{goal.id}:{month_key}",
                title=f"Увеличьте накопления на цель «{goal.name}»",
                text=f"Чтобы достичь цели к {goal.deadline.strftime('%d.%m.%Y')}, откладывайте "
                     f"{progress.recommended_monthly} {goal.currency} в месяц. Сейчас накоплено "
                     f"{progress.percent:.0f} % целевой суммы.",
                explanation="Рассчитано по остатку до целевой суммы и числу месяцев до срока; "
                            "при текущем темпе накоплений цель будет достигнута позже срока.",
                saving=ZERO,
            )
            if item:
                created.append(item)

    # 5. Низкая норма сбережений
    recent = load_transactions(db, user, today - timedelta(days=90), today)
    income, expense = totals(db, user, recent)
    if income > 0 and (income - expense) / income < Decimal("0.10"):
        target = _q(income / 3 * Decimal("0.10"))
        item = _add(
            db, user, existing, kind="savings_rate", key=f"savings:{month_key}",
            title="Откладывайте не менее 10 % дохода",
            text=f"За последние три месяца вы сохранили менее 10 % дохода. Попробуйте "
                 f"откладывать {target} {currency} сразу после получения дохода.",
            explanation=f"Доходы за 90 дней: {_q(income)} {currency}, расходы: "
                        f"{_q(expense)} {currency}.",
            saving=target,
        )
        if item:
            created.append(item)

    db.flush()
    if created:
        notify(db, user, "recommendation", "Новые рекомендации",
               f"Подготовлено рекомендаций: {len(created)}", payload={"count": len(created)})
    return created


def apply_recommendation(db: Session, user: User, recommendation: Recommendation) -> dict:
    """Применяет рекомендацию: выполняет связанное действие и фиксирует факт применения."""
    from .entities import save_entity  # локальный импорт: модуль entities использует insights

    result: dict = {}
    action = recommendation.action or {}
    if action.get("type") == "set_budget":
        existing = db.query(Budget).filter(
            Budget.user_id == user.id, Budget.deleted_at.is_(None),
            Budget.category_id == action["category_id"], Budget.period == action["period"],
        ).first()
        budget = save_entity(
            db, user, "budget", existing.id if existing else None,
            {"category_id": action["category_id"], "period": action["period"],
             "limit_amount": action["limit_amount"], "currency": user.base_currency},
        )
        result = {"budget_id": budget.id}
    recommendation.status = "applied"
    recommendation.applied_at = utcnow()
    return result


def liquid_reserve(db: Session, user: User) -> Decimal:
    balances = account_balances(db, user.id)
    total = ZERO
    for account in db.query(Account).filter(Account.user_id == user.id,
                                            Account.deleted_at.is_(None)):
        if account.type in ("cash", "bank"):
            total += safe_convert(db, balances.get(account.id, ZERO), account.currency,
                                  user.base_currency)
    return total
