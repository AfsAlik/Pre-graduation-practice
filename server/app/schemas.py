"""Схемы данных программного интерфейса (Pydantic).

Схемы *In описывают данные, принимаемые от мобильного приложения (в том числе
при синхронизации), схемы *Out – данные, возвращаемые клиенту. Денежные суммы
передаются десятичными числами в виде строк, чтобы исключить ошибки округления.
"""
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from typing import Annotated, Any, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    EmailStr,
    Field,
    PlainSerializer,
    model_validator,
)

from .models import TRADE_LOCK_DAYS

CurrencyCode = Annotated[str, Field(pattern=r"^[A-Z]{3}$")]
PositiveMoney = Annotated[Decimal, Field(gt=0, max_digits=18, decimal_places=2)]
NonNegativeMoney = Annotated[Decimal, Field(ge=0, max_digits=18, decimal_places=2)]
AnyMoney = Annotated[Decimal, Field(max_digits=18, decimal_places=2)]
PositiveQuantity = Annotated[Decimal, Field(gt=0, max_digits=24, decimal_places=8)]


def _money_text(value: Decimal) -> str:
    return format(Decimal(value).quantize(Decimal("0.01")), "f")


def _number_text(value: Decimal) -> str:
    text = format(Decimal(value), "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


# Суммы возвращаются строкой с двумя знаками после запятой: "1234.50"
MoneyOut = Annotated[Decimal, PlainSerializer(_money_text, return_type=str)]
# Количества и цены активов возвращаются без незначащих нулей: "0.015"
NumberOut = Annotated[Decimal, PlainSerializer(_number_text, return_type=str)]


class Orm(BaseModel):
    model_config = ConfigDict(from_attributes=True)


class SyncedOut(Orm):
    id: str
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None = None
    rev: int = 0


# ---------------------------------------------------------------- счета
class AccountIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    type: Literal["cash", "bank", "credit", "investment"] = "cash"
    currency: CurrencyCode = "RUB"
    initial_balance: AnyMoney = Decimal("0")
    is_archived: bool = False


class AccountOut(SyncedOut, AccountIn):
    initial_balance: MoneyOut = Decimal("0")
    balance: MoneyOut | None = None
    balance_base: MoneyOut | None = None


# ---------------------------------------------------------------- категории
class CategoryIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    kind: Literal["income", "expense"] = "expense"
    parent_id: str | None = None
    icon: str = Field(default="", max_length=50)
    color: str = Field(default="", max_length=9)


class CategoryOut(SyncedOut, CategoryIn):
    is_system: bool = False


# ---------------------------------------------------------------- транзакции
class TransactionIn(BaseModel):
    kind: Literal["income", "expense", "transfer"]
    amount: PositiveMoney
    # Валюта суммы; по умолчанию – валюта счета списания (зачисления)
    currency: CurrencyCode | None = None
    account_id: str
    to_account_id: str | None = None
    amount_to: PositiveMoney | None = None
    category_id: str | None = None
    occurred_at: datetime | None = None
    comment: str = Field(default="", max_length=500)
    merchant: str = Field(default="", max_length=200)
    source: Literal["manual", "template", "receipt", "voice", "import", "recurring"] = "manual"

    @model_validator(mode="after")
    def check_transfer(self):
        if self.kind == "transfer":
            if not self.to_account_id:
                raise ValueError("Для перевода необходимо указать счет зачисления")
            if self.to_account_id == self.account_id:
                raise ValueError("Счет списания и счет зачисления должны различаться")
        return self


class TransactionOut(SyncedOut):
    kind: str
    amount: MoneyOut
    currency: str
    account_id: str
    to_account_id: str | None = None
    amount_to: MoneyOut | None = None
    category_id: str | None = None
    occurred_at: datetime
    comment: str = ""
    merchant: str = ""
    source: str = "manual"
    receipt_file: str | None = None
    auto_categorized: bool = False
    category_confidence: float | None = None
    external_id: str | None = None


class TemplateIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    kind: Literal["income", "expense"] = "expense"
    amount: NonNegativeMoney = Decimal("0")
    currency: CurrencyCode = "RUB"
    account_id: str | None = None
    category_id: str | None = None
    comment: str = Field(default="", max_length=500)


class TemplateOut(SyncedOut, TemplateIn):
    amount: MoneyOut = Decimal("0")


class RecurringPaymentIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    amount: PositiveMoney
    currency: CurrencyCode = "RUB"
    account_id: str | None = None
    category_id: str | None = None
    period: Literal["week", "month", "year"] = "month"
    next_date: date
    remind_days_before: int = Field(default=1, ge=0, le=30)
    is_active: bool = True


class RecurringPaymentOut(SyncedOut, RecurringPaymentIn):
    amount: MoneyOut


# ---------------------------------------------------------------- бюджет и цели
class BudgetIn(BaseModel):
    category_id: str
    period: Literal["week", "month", "year"] = "month"
    limit_amount: PositiveMoney
    currency: CurrencyCode = "RUB"
    is_active: bool = True


class BudgetOut(SyncedOut, BudgetIn):
    limit_amount: MoneyOut


class BudgetStatusOut(BaseModel):
    budget: BudgetOut
    category_name: str
    period_start: date
    period_end: date
    spent: MoneyOut
    remaining: MoneyOut
    percent: float
    status: Literal["ok", "exceeded", "critical"]
    status_title: str
    warning: bool


class GoalIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    target_amount: PositiveMoney
    currency: CurrencyCode = "RUB"
    deadline: date
    priority: Literal["low", "medium", "high"] = "medium"


class GoalOut(SyncedOut, GoalIn):
    target_amount: MoneyOut
    status: str = "active"
    achieved_at: datetime | None = None


class GoalProgressOut(BaseModel):
    goal: GoalOut
    saved: MoneyOut
    remaining: MoneyOut
    percent: float
    forecast_date: date | None
    recommended_monthly: MoneyOut
    on_track: bool
    read_only: bool


class GoalContributionIn(BaseModel):
    goal_id: str
    amount: PositiveMoney
    occurred_at: datetime | None = None
    comment: str = Field(default="", max_length=300)


class GoalContributionOut(SyncedOut):
    goal_id: str
    amount: MoneyOut
    occurred_at: datetime
    comment: str = ""


# ---------------------------------------------------------------- инвестиции
class TradeIn(BaseModel):
    asset_type: Literal["stock", "bond", "etf", "currency", "crypto"]
    ticker: str = Field(min_length=1, max_length=30)
    name: str = Field(default="", max_length=150)
    side: Literal["buy", "sell"] = "buy"
    quantity: PositiveQuantity
    price: PositiveQuantity
    fee: NonNegativeMoney = Decimal("0")
    currency: CurrencyCode = "RUB"
    traded_at: datetime | None = None


class TradeOut(SyncedOut):
    asset_type: str
    ticker: str
    name: str = ""
    side: str
    quantity: NumberOut
    price: NumberOut
    fee: MoneyOut
    currency: str
    traded_at: datetime
    # Признак запрета изменения количества и цены (прошло более 7 дней с фиксации)
    locked: bool = False

    @model_validator(mode="after")
    def set_locked(self):
        created = self.created_at
        if created.tzinfo is None:
            created = created.replace(tzinfo=UTC)
        self.locked = datetime.now(UTC) - created > timedelta(days=TRADE_LOCK_DAYS)
        return self


class PositionOut(BaseModel):
    ticker: str
    name: str
    asset_type: str
    currency: str
    quantity: NumberOut
    average_price: NumberOut
    invested: MoneyOut
    fees: MoneyOut
    current_price: NumberOut | None
    price_as_of: datetime | None
    current_value: MoneyOut
    current_value_base: MoneyOut
    profit: MoneyOut
    profit_percent: float
    realized_profit: MoneyOut


class PortfolioOut(BaseModel):
    base_currency: str
    costing_method: str
    positions: list[PositionOut]
    total_invested_base: MoneyOut
    total_value_base: MoneyOut
    total_profit_base: MoneyOut
    total_profit_percent: float
    structure: dict[str, float]
    quotes_as_of: datetime | None
    quotes_stale: bool


# ---------------------------------------------------------------- синхронизация
class SyncChange(BaseModel):
    """Одна операция из очереди несинхронизированных операций клиента."""

    op_id: str = Field(min_length=8, max_length=36)
    entity: str
    id: str = Field(min_length=8, max_length=36)
    updated_at: datetime
    deleted: bool = False
    data: dict[str, Any] = Field(default_factory=dict)


class SyncRequest(BaseModel):
    device_id: str = ""
    since_rev: int = 0
    changes: list[SyncChange] = Field(default_factory=list, max_length=2000)


class SyncResult(BaseModel):
    op_id: str
    entity: str
    id: str
    status: Literal["applied", "duplicate", "conflict", "rejected"]
    message: str = ""


class SyncResponse(BaseModel):
    rev: int
    server_time: datetime
    results: list[SyncResult]
    changes: dict[str, list[dict[str, Any]]]


# ---------------------------------------------------------------- профиль
class NotificationSettings(BaseModel):
    push_enabled: bool = True
    email_enabled: bool = False
    budget_threshold_percent: int = Field(default=80, ge=10, le=100)
    frequency: Literal["instant", "daily"] = "instant"
    budget: bool = True
    recurring: bool = True
    goals: bool = True
    recommendations: bool = True
    risks: bool = True


class ProfileOut(Orm):
    id: str
    email: EmailStr
    display_name: str
    role: str
    base_currency: str
    risk_profile: str
    invest_horizon_years: int
    theme: str
    costing_method: str
    onboarding_done: bool
    totp_enabled: bool
    email_confirmed: bool
    notification_settings: NotificationSettings
    created_at: datetime


class ProfileUpdate(BaseModel):
    display_name: str | None = Field(default=None, max_length=100)
    base_currency: CurrencyCode | None = None
    risk_profile: Literal["conservative", "moderate", "aggressive"] | None = None
    invest_horizon_years: int | None = Field(default=None, ge=1, le=50)
    theme: Literal["light", "dark", "system"] | None = None
    costing_method: Literal["average", "fifo"] | None = None
    onboarding_done: bool | None = None
    notification_settings: NotificationSettings | None = None


class Message(BaseModel):
    message: str
