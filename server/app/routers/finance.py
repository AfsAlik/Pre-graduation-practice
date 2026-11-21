"""Учет транзакций и счетов, бюджетирование, цели и инвестиции.

Типовые операции (список, создание, изменение, удаление, восстановление)
формируются функцией crud_routes; все изменения проходят через общий модуль
services.entities, который применяет бизнес-правила и ведет аудит.
"""
from datetime import date, datetime, timedelta
from decimal import Decimal

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, Request, UploadFile
from pydantic import BaseModel, Field
from sqlalchemy import or_
from sqlalchemy.orm import Session

from .. import schemas
from ..db import contains, get_db
from ..models import (
    SYNC_ENTITIES,
    Category,
    Goal,
    GoalContribution,
    InvestmentTrade,
    Transaction,
    User,
)
from ..security import get_current_user
from ..services import entities, storage, textparse
from ..services.balances import account_balances
from ..services.budget import budget_status, budgets_overview
from ..services.categories import visible_categories
from ..services.currency import safe_convert
from ..services.goals import goal_progress
from ..services.ml_registry import suggest_category
from ..services.periods import day_end, day_start
from ..services.portfolio import asset_details, build_portfolio, is_locked, portfolio_dynamics
from ..services.quotes import ensure_history, refresh_user_quotes

router = APIRouter()


def crud_routes(entity: str, prefix: str, tag: str, schema_in, schema_out, *, listing: bool = True):
    """Добавляет типовые маршруты для синхронизируемой сущности."""
    model = SYNC_ENTITIES[entity]

    if listing:
        @router.get(prefix, response_model=list[schema_out], tags=[tag], name=f"list_{entity}")
        def list_items(include_deleted: bool = False, user: User = Depends(get_current_user),
                       db: Session = Depends(get_db)):
            query = db.query(model).filter(model.user_id == user.id)
            if not include_deleted:
                query = query.filter(model.deleted_at.is_(None))
            return query.order_by(model.created_at).all()

    @router.post(prefix, response_model=schema_out, status_code=201, tags=[tag],
                 name=f"create_{entity}")
    def create_item(data: schema_in, request: Request, user: User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
        return entities.save_entity(db, user, entity, None, data, request=request)

    @router.put(prefix + "/{item_id}", response_model=schema_out, tags=[tag],
                name=f"update_{entity}")
    def update_item(item_id: str, data: schema_in, request: Request,
                    user: User = Depends(get_current_user), db: Session = Depends(get_db)):
        entities.get_owned(db, user, entity, item_id)
        return entities.save_entity(db, user, entity, item_id, data, request=request)

    @router.delete(prefix + "/{item_id}", response_model=schemas.Message, tags=[tag],
                   name=f"delete_{entity}")
    def delete_item(item_id: str, request: Request, user: User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
        entities.delete_entity(db, user, entity, item_id, request=request)
        return schemas.Message(message=f"{entities.ENTITY_TITLES[entity]}: запись удалена")

    @router.post(prefix + "/{item_id}/restore", response_model=schema_out, tags=[tag],
                 name=f"restore_{entity}")
    def restore_item(item_id: str, request: Request, user: User = Depends(get_current_user),
                     db: Session = Depends(get_db)):
        return entities.restore_entity(db, user, entity, item_id, request=request)


# ------------------------------------------------------------------ счета
@router.get("/accounts", response_model=list[schemas.AccountOut], tags=["Счета"])
def list_accounts(include_deleted: bool = False, user: User = Depends(get_current_user),
                  db: Session = Depends(get_db)):
    """Счета пользователя с рассчитанными балансами и пересчетом в базовую валюту."""
    model = SYNC_ENTITIES["account"]
    query = db.query(model).filter(model.user_id == user.id)
    if not include_deleted:
        query = query.filter(model.deleted_at.is_(None))
    balances = account_balances(db, user.id)
    result = []
    for account in query.order_by(model.created_at).all():
        item = schemas.AccountOut.model_validate(account)
        item.balance = balances.get(account.id, Decimal("0"))
        item.balance_base = safe_convert(db, item.balance, account.currency, user.base_currency)
        result.append(item)
    return result


crud_routes("account", "/accounts", "Счета", schemas.AccountIn, schemas.AccountOut, listing=False)


# ------------------------------------------------------------------ категории
@router.get("/categories", response_model=list[schemas.CategoryOut], tags=["Категории"])
def list_categories(kind: str | None = None, user: User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
    """Справочник категорий: системные и созданные пользователем."""
    query = visible_categories(db, user.id)
    if kind:
        query = query.filter(Category.kind == kind)
    return query.order_by(Category.is_system.desc(), Category.name).all()


crud_routes("category", "/categories", "Категории", schemas.CategoryIn, schemas.CategoryOut,
            listing=False)


# ------------------------------------------------------------------ транзакции
class TransactionPage(BaseModel):
    total: int
    items: list[schemas.TransactionOut]


class ParseTextIn(BaseModel):
    text: str = Field(min_length=1, max_length=5000)


class SuggestOut(BaseModel):
    category_id: str | None = None
    category_name: str | None = None
    confidence: float | None = None
    source: str | None = None


class ParsedTransactionOut(BaseModel):
    kind: str = "expense"
    amount: schemas.MoneyOut | None = None
    occurred_at: datetime | None = None
    merchant: str = ""
    category: SuggestOut = SuggestOut()
    receipt_file: str | None = None


@router.get("/transactions", response_model=TransactionPage, tags=["Транзакции"])
def list_transactions(
    date_from: date | None = None, date_to: date | None = None,
    category_id: list[str] | None = Query(default=None),
    account_id: list[str] | None = Query(default=None),
    kind: str | None = None, amount_min: Decimal | None = None, amount_max: Decimal | None = None,
    search: str | None = None, deleted: bool = False,
    limit: int = Query(default=50, ge=1, le=500), offset: int = Query(default=0, ge=0),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
):
    """История операций с фильтрацией по дате, категории, счету, сумме и поиском."""
    query = db.query(Transaction).filter(Transaction.user_id == user.id)
    query = query.filter(
        Transaction.deleted_at.is_not(None) if deleted else Transaction.deleted_at.is_(None))
    if date_from:
        query = query.filter(Transaction.occurred_at >= day_start(date_from))
    if date_to:
        query = query.filter(Transaction.occurred_at <= day_end(date_to))
    if category_id:
        query = query.filter(Transaction.category_id.in_(category_id))
    if account_id:
        query = query.filter(or_(Transaction.account_id.in_(account_id),
                                 Transaction.to_account_id.in_(account_id)))
    if kind:
        query = query.filter(Transaction.kind == kind)
    if amount_min is not None:
        query = query.filter(Transaction.amount >= amount_min)
    if amount_max is not None:
        query = query.filter(Transaction.amount <= amount_max)
    if search:
        query = query.filter(or_(contains(Transaction.comment, search),
                                 contains(Transaction.merchant, search)))
    total = query.count()
    items = query.order_by(Transaction.occurred_at.desc(), Transaction.id).offset(offset).limit(limit).all()
    return TransactionPage(total=total, items=items)


@router.post("/transactions/suggest-category", response_model=SuggestOut, tags=["Транзакции"])
def suggest(data: ParseTextIn, kind: str = "expense", user: User = Depends(get_current_user),
            db: Session = Depends(get_db)):
    """Автоматическое определение категории по тексту операции."""
    return SuggestOut(**(suggest_category(db, user.id, data.text, kind) or {}))


@router.post("/transactions/parse-receipt", response_model=ParsedTransactionOut, tags=["Транзакции"])
async def parse_receipt(
    text: str = Form(..., max_length=20000), photo: UploadFile | None = File(default=None),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
):
    """Выделяет сумму и категорию из распознанного текста чека; сохраняет фотографию."""
    parsed = textparse.parse_receipt(text)
    receipt_file = None
    if photo is not None:
        content = await photo.read()
        if len(content) > 10 * 1024 * 1024:
            raise HTTPException(413, "Размер фотографии не должен превышать 10 МБ")
        receipt_file = storage.save(f"receipts/{user.id}", photo.filename or "receipt.jpg", content)
    suggestion = suggest_category(db, user.id, parsed["hint"], "expense") if parsed["hint"] else None
    return ParsedTransactionOut(
        kind="expense", amount=parsed["amount"], occurred_at=parsed["occurred_at"],
        merchant=parsed["merchant"], category=SuggestOut(**(suggestion or {})),
        receipt_file=receipt_file,
    )


@router.post("/transactions/parse-voice", response_model=ParsedTransactionOut, tags=["Транзакции"])
def parse_voice(data: ParseTextIn, user: User = Depends(get_current_user),
                db: Session = Depends(get_db)):
    """Разбирает распознанную голосовую фразу: вид операции, сумма, категория."""
    parsed = textparse.parse_voice(data.text)
    suggestion = None
    if parsed["description"]:
        suggestion = suggest_category(db, user.id, parsed["description"], parsed["kind"])
    return ParsedTransactionOut(
        kind=parsed["kind"], amount=parsed["amount"], merchant=parsed["description"],
        category=SuggestOut(**(suggestion or {})),
    )


@router.post("/transactions/{item_id}/receipt", response_model=schemas.TransactionOut,
             tags=["Транзакции"])
def attach_receipt(item_id: str, receipt_file: str = Form(...),
                   user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    transaction = entities.get_owned(db, user, "transaction", item_id)
    if not receipt_file.startswith(f"receipts/{user.id}/"):
        raise HTTPException(403, "Файл чека принадлежит другому пользователю")
    transaction.receipt_file = receipt_file
    return transaction


crud_routes("transaction", "/transactions", "Транзакции", schemas.TransactionIn,
            schemas.TransactionOut, listing=False)
crud_routes("template", "/templates", "Шаблоны", schemas.TemplateIn, schemas.TemplateOut)
crud_routes("recurring_payment", "/recurring-payments", "Регулярные платежи",
            schemas.RecurringPaymentIn, schemas.RecurringPaymentOut)


# ------------------------------------------------------------------ бюджет
@router.get("/budgets/status", response_model=list[schemas.BudgetStatusOut], tags=["Бюджет"])
def budgets_state(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    """Исполнение бюджета: израсходовано, остаток, процент и статус по каждому лимиту."""
    return budgets_overview(db, user)


@router.get("/budgets/{item_id}/status", response_model=schemas.BudgetStatusOut, tags=["Бюджет"])
def one_budget_state(item_id: str, user: User = Depends(get_current_user),
                     db: Session = Depends(get_db)):
    return budget_status(db, user, entities.get_owned(db, user, "budget", item_id))


crud_routes("budget", "/budgets", "Бюджет", schemas.BudgetIn, schemas.BudgetOut)


# ------------------------------------------------------------------ цели
@router.get("/goals/progress", response_model=list[schemas.GoalProgressOut], tags=["Цели"])
def goals_progress(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    """Цели с прогрессом, прогнозируемой датой и рекомендуемой суммой накоплений."""
    goals = db.query(Goal).filter(Goal.user_id == user.id, Goal.deleted_at.is_(None)) \
        .order_by(Goal.status, Goal.deadline).all()
    return [goal_progress(db, goal) for goal in goals]


@router.get("/goals/{item_id}/progress", response_model=schemas.GoalProgressOut, tags=["Цели"])
def one_goal_progress(item_id: str, user: User = Depends(get_current_user),
                      db: Session = Depends(get_db)):
    return goal_progress(db, entities.get_owned(db, user, "goal", item_id))


@router.get("/goals/{item_id}/contributions", response_model=list[schemas.GoalContributionOut],
            tags=["Цели"])
def goal_contributions(item_id: str, user: User = Depends(get_current_user),
                       db: Session = Depends(get_db)):
    entities.get_owned(db, user, "goal", item_id)
    return db.query(GoalContribution).filter(
        GoalContribution.goal_id == item_id, GoalContribution.deleted_at.is_(None)
    ).order_by(GoalContribution.occurred_at).all()


crud_routes("goal", "/goals", "Цели", schemas.GoalIn, schemas.GoalOut)
crud_routes("goal_contribution", "/goal-contributions", "Цели", schemas.GoalContributionIn,
            schemas.GoalContributionOut)


# ------------------------------------------------------------------ инвестиции
@router.get("/investments/trades", response_model=list[schemas.TradeOut], tags=["Инвестиции"])
def list_trades(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    trades = db.query(InvestmentTrade).filter(
        InvestmentTrade.user_id == user.id, InvestmentTrade.deleted_at.is_(None)
    ).order_by(InvestmentTrade.traded_at.desc()).all()
    result = []
    for trade in trades:
        item = schemas.TradeOut.model_validate(trade)
        item.locked = is_locked(trade)
        result.append(item)
    return result


crud_routes("investment_trade", "/investments/trades", "Инвестиции", schemas.TradeIn,
            schemas.TradeOut, listing=False)


@router.get("/investments/portfolio", response_model=schemas.PortfolioOut, tags=["Инвестиции"])
def portfolio(refresh: bool = True, user: User = Depends(get_current_user),
              db: Session = Depends(get_db)):
    """Портфель: позиции, текущая стоимость и доходность.

    При открытии портфеля рыночные цены обновляются из внешнего источника
    (п. 4.2.2.15 ТЗ); при его недоступности используются сохраненные цены.
    """
    if refresh:
        refresh_user_quotes(db, user.id)
    return build_portfolio(db, user)


@router.get("/investments/dynamics", tags=["Инвестиции"])
def dynamics(period: str = Query(default="month", pattern="^(day|month|year)$"),
             user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    """Динамика стоимости портфеля за день, месяц или год."""
    return {"period": period, "currency": user.base_currency,
            "points": portfolio_dynamics(db, user, period)}


@router.get("/investments/assets/{ticker}", tags=["Инвестиции"])
def asset(ticker: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    """Детальная аналитика по активу: история цен, доходность за периоды, комиссии."""
    trade = db.query(InvestmentTrade).filter(
        InvestmentTrade.user_id == user.id, InvestmentTrade.ticker == ticker.upper(),
        InvestmentTrade.deleted_at.is_(None),
    ).first()
    if trade is None:
        raise HTTPException(404, "Актив не найден в портфеле")
    ensure_history(db, trade.ticker, trade.asset_type, date.today() - timedelta(days=365))
    return asset_details(db, user, ticker)
