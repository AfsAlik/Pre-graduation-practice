"""Единые правила сохранения и удаления синхронизируемых сущностей.

Через функции этого модуля проходят и запросы программного интерфейса, и
операции, принятые при синхронизации из автономного режима. Благодаря этому
проверки данных, бизнес-правила, уведомления и аудит выполняются одинаково
независимо от способа поступления изменения.
"""
from datetime import UTC, date, datetime
from decimal import Decimal

from fastapi import HTTPException, Request
from pydantic import BaseModel, ValidationError
from sqlalchemy.orm import Session

from .. import schemas
from ..audit import audit
from ..models import (
    SYNC_ENTITIES,
    Account,
    Budget,
    Category,
    Goal,
    GoalContribution,
    InvestmentTrade,
    Transaction,
    User,
    utcnow,
)
from .budget import check_budgets_for_category
from .categories import visible_categories
from .currency import safe_convert
from .goals import refresh_goal_state
from .ml_registry import remember_sample, suggest_category
from .portfolio import calculate_position, is_locked
from .revisions import touch
from .tasks import enqueue

IN_SCHEMAS: dict[str, type[BaseModel]] = {
    "account": schemas.AccountIn,
    "category": schemas.CategoryIn,
    "transaction": schemas.TransactionIn,
    "template": schemas.TemplateIn,
    "recurring_payment": schemas.RecurringPaymentIn,
    "budget": schemas.BudgetIn,
    "goal": schemas.GoalIn,
    "goal_contribution": schemas.GoalContributionIn,
    "investment_trade": schemas.TradeIn,
}
OUT_SCHEMAS: dict[str, type[BaseModel]] = {
    "account": schemas.AccountOut,
    "category": schemas.CategoryOut,
    "transaction": schemas.TransactionOut,
    "template": schemas.TemplateOut,
    "recurring_payment": schemas.RecurringPaymentOut,
    "budget": schemas.BudgetOut,
    "goal": schemas.GoalOut,
    "goal_contribution": schemas.GoalContributionOut,
    "investment_trade": schemas.TradeOut,
}
ENTITY_TITLES = {
    "account": "Счет", "category": "Категория", "transaction": "Транзакция",
    "template": "Шаблон", "recurring_payment": "Регулярный платеж", "budget": "Бюджет",
    "goal": "Цель", "goal_contribution": "Накопление", "investment_trade": "Сделка",
}
AUDITED = {"account", "transaction", "budget", "goal", "goal_contribution", "investment_trade"}


class RuleViolation(HTTPException):
    """Нарушение бизнес-правила; при синхронизации операция отклоняется."""

    def __init__(self, message: str, status_code: int = 409):
        super().__init__(status_code, message)
        self.message = message


def serialize(entity: str, obj) -> dict:
    data = OUT_SCHEMAS[entity].model_validate(obj).model_dump(mode="json")
    if entity == "investment_trade":
        data["locked"] = is_locked(obj)
    return data


def get_owned(db: Session, user: User, entity: str, entity_id: str, *, include_deleted=False):
    model = SYNC_ENTITIES[entity]
    obj = db.get(model, entity_id)
    if entity == "category" and obj is not None and obj.is_system:
        raise RuleViolation("Системную категорию нельзя изменить или удалить", 403)
    if obj is None or obj.user_id != user.id or (obj.deleted_at and not include_deleted):
        raise HTTPException(404, f"{ENTITY_TITLES[entity]} не найден(а)")
    return obj


def _aware(value: datetime | None) -> datetime | None:
    if value is not None and value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value


def _account(db: Session, user: User, account_id: str | None, *, required=True) -> Account | None:
    if not account_id:
        if required:
            raise RuleViolation("Не указан счет", 422)
        return None
    account = db.get(Account, account_id)
    if account is None or account.user_id != user.id or account.deleted_at is not None:
        raise RuleViolation("Счет не найден", 422)
    return account


def _category(db: Session, user: User, category_id: str | None, kind: str | None) -> Category | None:
    if not category_id:
        return None
    category = visible_categories(db, user.id).filter(Category.id == category_id).first()
    if category is None:
        raise RuleViolation("Категория не найдена", 422)
    if kind in ("income", "expense") and category.kind != kind:
        raise RuleViolation("Вид категории не соответствует виду операции", 422)
    return category


# ----------------------------------------------------------- правила по сущностям
def _prepare_category(db, user, obj: Category | None, data: dict) -> dict:
    if obj is not None and obj.is_system:
        raise RuleViolation("Системную категорию нельзя изменить", 403)
    if data.get("parent_id"):
        parent = _category(db, user, data["parent_id"], None)
        if parent.kind != data["kind"]:
            raise RuleViolation("Подкатегория должна иметь тот же вид, что и категория", 422)
        if obj is not None and parent.id == obj.id:
            raise RuleViolation("Категория не может быть вложена сама в себя", 422)
    return data


def _prepare_transaction(db, user, obj: Transaction | None, data: dict) -> dict:
    account = _account(db, user, data["account_id"])
    currency = data.get("currency") or account.currency
    if currency != account.currency:
        # Сумма введена в другой валюте: пересчитывается в валюту счета по курсу
        original = f"{Decimal(data['amount']):.2f} {currency}"
        data["amount"] = safe_convert(db, data["amount"], currency, account.currency)
        note = f"(исходная сумма: {original})"
        if note not in data.get("comment", ""):
            data["comment"] = f"{data.get('comment', '')} {note}".strip()[:500]
    data["currency"] = account.currency
    data["occurred_at"] = _aware(data.get("occurred_at")) or utcnow()
    if data["kind"] == "transfer":
        target = _account(db, user, data["to_account_id"])
        data["category_id"] = None
        if target.currency == account.currency:
            data["amount_to"] = data["amount"]
        elif not data.get("amount_to"):
            data["amount_to"] = safe_convert(
                db, data["amount"], account.currency, target.currency, data["occurred_at"].date()
            )
        return data
    data["to_account_id"], data["amount_to"] = None, None
    if data.get("category_id"):
        _category(db, user, data["category_id"], data["kind"])
        category_changed = obj is None or obj.category_id != data["category_id"]
        if category_changed:
            data["auto_categorized"], data["category_confidence"] = False, None
    else:
        # Категория не указана: автоматическая категоризация (п. 4.2.1.1 г ТЗ)
        text = f"{data.get('merchant', '')} {data.get('comment', '')}".strip()
        suggestion = suggest_category(db, user.id, text, data["kind"]) if text else None
        if suggestion is not None:
            data["category_id"] = suggestion["category_id"]
            data["auto_categorized"] = True
            data["category_confidence"] = suggestion["confidence"]
    return data


def _prepare_budget(db, user, obj: Budget | None, data: dict) -> dict:
    category = _category(db, user, data["category_id"], None)
    if category is None or category.kind != "expense":
        raise RuleViolation("Бюджетный лимит устанавливается для категории расходов", 422)
    duplicate = db.query(Budget).filter(
        Budget.user_id == user.id, Budget.deleted_at.is_(None),
        Budget.category_id == data["category_id"], Budget.period == data["period"],
    )
    if obj is not None:
        duplicate = duplicate.filter(Budget.id != obj.id)
    if duplicate.first() is not None:
        raise RuleViolation("Лимит для этой категории и периода уже установлен")
    if obj is not None and (obj.limit_amount != data["limit_amount"] or obj.period != data["period"]):
        data["warned_period"], data["exceeded_period"] = None, None
    return data


def _prepare_goal(db, user, obj: Goal | None, data: dict) -> dict:
    if obj is not None and obj.status == "achieved":
        raise RuleViolation("Достигнутая цель доступна только для просмотра")
    if obj is None and data["deadline"] <= date.today():
        raise RuleViolation("Срок достижения цели должен быть в будущем", 422)
    return data


def _prepare_contribution(db, user, obj: GoalContribution | None, data: dict) -> dict:
    goal = db.get(Goal, data["goal_id"])
    if goal is None or goal.user_id != user.id or goal.deleted_at is not None:
        raise RuleViolation("Цель не найдена", 422)
    if goal.status == "achieved":
        raise RuleViolation("Достигнутая цель доступна только для просмотра")
    data["occurred_at"] = _aware(data.get("occurred_at")) or utcnow()
    return data


def _prepare_trade(db, user, obj: InvestmentTrade | None, data: dict) -> dict:
    data["ticker"] = data["ticker"].upper().strip()
    data["traded_at"] = _aware(data.get("traded_at")) or utcnow()
    if obj is not None and is_locked(obj):
        if Decimal(obj.quantity) != data["quantity"] or Decimal(obj.price) != data["price"]:
            raise RuleViolation(
                "Количество и цену покупки нельзя изменить спустя 7 дней после фиксации "
                "позиции. Оформите корректировку новой сделкой."
            )
    if data["side"] == "sell":
        others = db.query(InvestmentTrade).filter(
            InvestmentTrade.user_id == user.id, InvestmentTrade.deleted_at.is_(None),
            InvestmentTrade.ticker == data["ticker"],
        )
        if obj is not None:
            others = others.filter(InvestmentTrade.id != obj.id)
        available = calculate_position(others.all())["quantity"]
        if data["quantity"] > available:
            raise RuleViolation("Количество в сделке продажи превышает размер позиции", 422)
    return data


def _prepare_reference(db, user, obj, data: dict) -> dict:
    _account(db, user, data.get("account_id"), required=False)
    _category(db, user, data.get("category_id"), data.get("kind"))
    return data


_PREPARE = {
    "category": _prepare_category,
    "transaction": _prepare_transaction,
    "template": _prepare_reference,
    "recurring_payment": _prepare_reference,
    "budget": _prepare_budget,
    "goal": _prepare_goal,
    "goal_contribution": _prepare_contribution,
    "investment_trade": _prepare_trade,
}


def _after_save(db: Session, user: User, entity: str, obj, created: bool) -> None:
    if entity == "transaction":
        if obj.kind == "expense":
            check_budgets_for_category(db, user, obj.category_id)
        if not obj.auto_categorized:
            remember_sample(db, user.id, obj)
    elif entity == "budget":
        check_budgets_for_category(db, user, obj.category_id)
    elif entity == "goal_contribution":
        goal = db.get(Goal, obj.goal_id)
        if goal is not None and refresh_goal_state(db, user, goal):
            touch(db, goal)
    elif entity == "goal":
        if refresh_goal_state(db, user, obj):
            touch(db, obj)
    elif entity == "investment_trade" and created:
        enqueue(db, "refresh_quote", ticker=obj.ticker, asset_type=obj.asset_type)


# ----------------------------------------------------------- общие операции
def validate(entity: str, data: dict) -> dict:
    try:
        return IN_SCHEMAS[entity].model_validate(data).model_dump()
    except ValidationError as exc:
        first = exc.errors()[0]
        field = ".".join(str(p) for p in first["loc"])
        raise RuleViolation(f"Некорректные данные ({field}): {first['msg']}", 422) from exc


def save_entity(
    db: Session, user: User, entity: str, entity_id: str | None, data: dict | BaseModel, *,
    updated_at: datetime | None = None, request: Request | None = None,
):
    """Создает или изменяет сущность пользователя и возвращает ее."""
    if isinstance(data, BaseModel):
        data = data.model_dump()
    data = validate(entity, data)
    model = SYNC_ENTITIES[entity]
    obj = db.get(model, entity_id) if entity_id else None
    if obj is not None and obj.user_id != user.id:
        raise HTTPException(404, f"{ENTITY_TITLES[entity]} не найден(а)")
    created = obj is None
    prepare = _PREPARE.get(entity)
    if prepare is not None:
        data = prepare(db, user, obj, data)
    if created:
        obj = model(user_id=user.id, **data)
        if entity_id:
            obj.id = entity_id
        db.add(obj)
    else:
        for key, value in data.items():
            setattr(obj, key, value)
        obj.deleted_at = None
    touch(db, obj, updated_at=_aware(updated_at))
    db.flush()
    _after_save(db, user, entity, obj, created)
    if entity in AUDITED:
        audit(db, f"{entity}.{'create' if created else 'update'}", user=user, entity=entity,
              entity_id=obj.id, request=request)
    db.flush()
    return obj


def delete_entity(
    db: Session, user: User, entity: str, entity_id: str, *,
    updated_at: datetime | None = None, request: Request | None = None,
):
    """Помечает сущность удаленной. Удаленные транзакции хранятся 30 дней."""
    obj = get_owned(db, user, entity, entity_id, include_deleted=True)
    if obj.deleted_at is not None:
        return obj
    if entity == "category" and obj.is_system:
        raise RuleViolation("Системную категорию нельзя удалить", 403)
    if entity == "goal" and obj.status == "achieved":
        raise RuleViolation("Достигнутая цель доступна только для просмотра")
    if entity == "investment_trade" and is_locked(obj):
        raise RuleViolation(
            "Сделку нельзя удалить спустя 7 дней после фиксации позиции. "
            "Оформите корректировку новой сделкой."
        )
    if entity == "account":
        used = db.query(Transaction.id).filter(
            Transaction.user_id == user.id, Transaction.deleted_at.is_(None),
            (Transaction.account_id == obj.id) | (Transaction.to_account_id == obj.id),
        ).first()
        if used is not None:
            raise RuleViolation("По счету есть операции. Переведите счет в архив")
    obj.deleted_at = utcnow()
    touch(db, obj, updated_at=_aware(updated_at))
    if entity == "goal_contribution":
        goal = db.get(Goal, obj.goal_id)
        if goal is not None:
            touch(db, goal)
    if entity in AUDITED:
        audit(db, f"{entity}.delete", user=user, entity=entity, entity_id=obj.id, request=request)
    db.flush()
    return obj


def restore_entity(db: Session, user: User, entity: str, entity_id: str, *, request=None):
    """Восстанавливает удаленную сущность со всеми атрибутами."""
    obj = get_owned(db, user, entity, entity_id, include_deleted=True)
    if obj.deleted_at is None:
        return obj
    obj.deleted_at = None
    touch(db, obj)
    if entity == "transaction" and obj.kind == "expense":
        check_budgets_for_category(db, user, obj.category_id)
    if entity in AUDITED:
        audit(db, f"{entity}.restore", user=user, entity=entity, entity_id=obj.id, request=request)
    db.flush()
    return obj
