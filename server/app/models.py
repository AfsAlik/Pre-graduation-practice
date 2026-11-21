"""Модели данных серверной части (реляционная модель, п. 4.3.2 ТЗ).

Сущности, которые редактируются на мобильном устройстве в автономном режиме,
наследуют SyncMixin: идентификатор формируется на клиенте (UUID), удаление
выполняется пометкой deleted_at, а поле rev хранит номер ревизии пользователя,
по которому мобильное приложение получает изменения при синхронизации.
"""
import uuid
from datetime import UTC, date, datetime
from decimal import Decimal

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.types import TypeDecorator

from .db import Base


class UtcDateTime(TypeDecorator):
    """Момент времени в UTC.

    Значение всегда сохраняется в UTC и возвращается с указанием часового пояса
    независимо от СУБД (SQLite не хранит часовой пояс).
    """

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is not None and value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC) if value is not None else None

    def process_result_value(self, value, dialect):
        if value is not None and value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value


# Срок, после которого количество и цену сделки изменить нельзя (п. 4.2.1.3 е ТЗ)
TRADE_LOCK_DAYS = 7
Money = Numeric(18, 2)
Quantity = Numeric(24, 8)


def utcnow() -> datetime:
    return datetime.now(UTC)


def new_id() -> str:
    return str(uuid.uuid4())


class SyncMixin:
    """Общие поля синхронизируемых сущностей."""

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), index=True)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    rev: Mapped[int] = mapped_column(BigInteger, default=0, index=True)


# --------------------------------------------------------------------------
# Пользователи и безопасность
# --------------------------------------------------------------------------
class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    display_name: Mapped[str] = mapped_column(String(100), default="")
    password_hash: Mapped[str] = mapped_column(String(255))
    role: Mapped[str] = mapped_column(String(20), default="user")  # user | analyst | admin
    is_blocked: Mapped[bool] = mapped_column(Boolean, default=False)
    email_confirmed: Mapped[bool] = mapped_column(Boolean, default=False)
    consent_pd_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    # Профиль (п. 4.2.1.8 г)
    base_currency: Mapped[str] = mapped_column(String(3), default="RUB")
    risk_profile: Mapped[str] = mapped_column(String(20), default="moderate")
    invest_horizon_years: Mapped[int] = mapped_column(Integer, default=3)
    theme: Mapped[str] = mapped_column(String(10), default="system")
    costing_method: Mapped[str] = mapped_column(String(10), default="average")  # average | fifo
    onboarding_done: Mapped[bool] = mapped_column(Boolean, default=False)
    notification_settings: Mapped[dict] = mapped_column(JSON, default=dict)
    # Двухфакторная аутентификация
    totp_secret: Mapped[str | None] = mapped_column(String(64))
    totp_enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    # Защита от подбора пароля (п. 4.4.7.6)
    failed_logins: Mapped[int] = mapped_column(Integer, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(UtcDateTime)
    # Синхронизация
    sync_rev: Mapped[int] = mapped_column(BigInteger, default=0)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    last_login_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    deleted_at: Mapped[datetime | None] = mapped_column(UtcDateTime)


class AuthToken(Base):
    """Одноразовые и обновляемые токены: подтверждение почты, сброс пароля, refresh."""

    __tablename__ = "auth_tokens"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), index=True)
    kind: Mapped[str] = mapped_column(String(20))  # email_confirm | password_reset | refresh
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    expires_at: Mapped[datetime] = mapped_column(UtcDateTime)
    used_at: Mapped[datetime | None] = mapped_column(UtcDateTime)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)


class Device(Base):
    """Мобильное устройство пользователя и его токен доставки push-уведомлений."""

    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), index=True)
    push_token: Mapped[str] = mapped_column(String(512), default="")
    platform: Mapped[str] = mapped_column(String(20), default="android")
    model: Mapped[str] = mapped_column(String(100), default="")
    last_seen_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)


class BankConsent(Base):
    """Согласие пользователя на подключение внешнего источника банковских данных."""

    __tablename__ = "bank_consents"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), index=True)
    provider: Mapped[str] = mapped_column(String(50))
    granted_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    revoked_at: Mapped[datetime | None] = mapped_column(UtcDateTime)


# --------------------------------------------------------------------------
# Учет транзакций и счетов
# --------------------------------------------------------------------------
class Account(SyncMixin, Base):
    __tablename__ = "accounts"

    name: Mapped[str] = mapped_column(String(100))
    type: Mapped[str] = mapped_column(String(20), default="cash")  # cash|bank|credit|investment
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    initial_balance: Mapped[Decimal] = mapped_column(Money, default=Decimal("0"))
    is_archived: Mapped[bool] = mapped_column(Boolean, default=False)


class Category(SyncMixin, Base):
    """Категория или подкатегория. Системные категории имеют is_system = True."""

    __tablename__ = "categories"

    name: Mapped[str] = mapped_column(String(100))
    kind: Mapped[str] = mapped_column(String(10), default="expense")  # income | expense
    parent_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    icon: Mapped[str] = mapped_column(String(50), default="")
    color: Mapped[str] = mapped_column(String(9), default="")
    is_system: Mapped[bool] = mapped_column(Boolean, default=False)


class Transaction(SyncMixin, Base):
    __tablename__ = "transactions"
    __table_args__ = (
        Index("ix_transactions_user_date", "user_id", "occurred_at"),
        Index("ix_transactions_user_category", "user_id", "category_id"),
    )

    kind: Mapped[str] = mapped_column(String(10))  # income | expense | transfer
    amount: Mapped[Decimal] = mapped_column(Money)
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    account_id: Mapped[str] = mapped_column(String(36), index=True)
    to_account_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    amount_to: Mapped[Decimal | None] = mapped_column(Money, nullable=True)
    category_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    comment: Mapped[str] = mapped_column(String(500), default="")
    merchant: Mapped[str] = mapped_column(String(200), default="")
    source: Mapped[str] = mapped_column(String(20), default="manual")
    receipt_file: Mapped[str | None] = mapped_column(String(255), nullable=True)
    auto_categorized: Mapped[bool] = mapped_column(Boolean, default=False)
    category_confidence: Mapped[float | None] = mapped_column(Float, nullable=True)
    # Идентификатор операции во внешнем источнике (защита от повторного импорта)
    external_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)


class TransactionTemplate(SyncMixin, Base):
    __tablename__ = "transaction_templates"

    name: Mapped[str] = mapped_column(String(100))
    kind: Mapped[str] = mapped_column(String(10), default="expense")
    amount: Mapped[Decimal] = mapped_column(Money, default=Decimal("0"))
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    account_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    category_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    comment: Mapped[str] = mapped_column(String(500), default="")


class RecurringPayment(SyncMixin, Base):
    __tablename__ = "recurring_payments"

    name: Mapped[str] = mapped_column(String(100))
    amount: Mapped[Decimal] = mapped_column(Money)
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    account_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    category_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    period: Mapped[str] = mapped_column(String(10), default="month")  # week | month | year
    next_date: Mapped[date] = mapped_column(Date)
    remind_days_before: Mapped[int] = mapped_column(Integer, default=1)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    last_reminded_for: Mapped[date | None] = mapped_column(Date, nullable=True)


# --------------------------------------------------------------------------
# Бюджетирование и цели
# --------------------------------------------------------------------------
class Budget(SyncMixin, Base):
    __tablename__ = "budgets"

    category_id: Mapped[str] = mapped_column(String(36), index=True)
    period: Mapped[str] = mapped_column(String(10), default="month")  # week | month | year
    limit_amount: Mapped[Decimal] = mapped_column(Money)
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    # Период, для которого уже отправлены уведомления (чтобы не дублировать их)
    warned_period: Mapped[str | None] = mapped_column(String(20), nullable=True)
    exceeded_period: Mapped[str | None] = mapped_column(String(20), nullable=True)


class Goal(SyncMixin, Base):
    __tablename__ = "goals"

    name: Mapped[str] = mapped_column(String(100))
    target_amount: Mapped[Decimal] = mapped_column(Money)
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    deadline: Mapped[date] = mapped_column(Date)
    priority: Mapped[str] = mapped_column(String(10), default="medium")  # low | medium | high
    status: Mapped[str] = mapped_column(String(10), default="active")  # active | achieved
    achieved_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)


class GoalContribution(SyncMixin, Base):
    __tablename__ = "goal_contributions"

    goal_id: Mapped[str] = mapped_column(String(36), index=True)
    amount: Mapped[Decimal] = mapped_column(Money)
    occurred_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    comment: Mapped[str] = mapped_column(String(300), default="")


# --------------------------------------------------------------------------
# Инвестиционный учет
# --------------------------------------------------------------------------
class InvestmentTrade(SyncMixin, Base):
    """Сделка с инвестиционным инструментом. Позиция рассчитывается по сделкам."""

    __tablename__ = "investment_trades"
    __table_args__ = (Index("ix_trades_user_ticker", "user_id", "ticker"),)

    asset_type: Mapped[str] = mapped_column(String(10))  # stock|bond|etf|currency|crypto
    ticker: Mapped[str] = mapped_column(String(30))
    name: Mapped[str] = mapped_column(String(150), default="")
    side: Mapped[str] = mapped_column(String(4), default="buy")  # buy | sell
    quantity: Mapped[Decimal] = mapped_column(Quantity)
    price: Mapped[Decimal] = mapped_column(Quantity)
    fee: Mapped[Decimal] = mapped_column(Money, default=Decimal("0"))
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    traded_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)


class Quote(Base):
    """Последняя известная рыночная цена актива (кеш внешнего источника)."""

    __tablename__ = "quotes"

    ticker: Mapped[str] = mapped_column(String(30), primary_key=True)
    asset_type: Mapped[str] = mapped_column(String(10), default="stock")
    price: Mapped[Decimal] = mapped_column(Quantity)
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    as_of: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    source: Mapped[str] = mapped_column(String(30), default="")


class QuoteHistory(Base):
    __tablename__ = "quote_history"
    __table_args__ = (UniqueConstraint("ticker", "day", name="uq_quote_history"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ticker: Mapped[str] = mapped_column(String(30), index=True)
    day: Mapped[date] = mapped_column(Date)
    price: Mapped[Decimal] = mapped_column(Quantity)


class ExchangeRate(Base):
    """Курс валюты к рублю на дату (кеш внешнего источника)."""

    __tablename__ = "exchange_rates"
    __table_args__ = (UniqueConstraint("currency", "day", name="uq_exchange_rate"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    currency: Mapped[str] = mapped_column(String(3), index=True)
    day: Mapped[date] = mapped_column(Date)
    rate_to_rub: Mapped[Decimal] = mapped_column(Numeric(18, 6))
    fetched_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    source: Mapped[str] = mapped_column(String(30), default="")


# --------------------------------------------------------------------------
# Интеллектуальные рекомендации
# --------------------------------------------------------------------------
class Recommendation(Base):
    __tablename__ = "recommendations"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), index=True)
    kind: Mapped[str] = mapped_column(String(30))
    dedup_key: Mapped[str] = mapped_column(String(120), default="")
    title: Mapped[str] = mapped_column(String(200))
    text: Mapped[str] = mapped_column(Text)
    explanation: Mapped[str] = mapped_column(Text, default="")
    estimated_saving: Mapped[Decimal] = mapped_column(Money, default=Decimal("0"))
    action: Mapped[dict] = mapped_column(JSON, default=dict)
    status: Mapped[str] = mapped_column(String(10), default="new")  # new|applied|dismissed
    feedback: Mapped[str | None] = mapped_column(String(12), nullable=True)  # useful|not_useful
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    applied_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)


class RiskAlert(Base):
    __tablename__ = "risk_alerts"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), index=True)
    kind: Mapped[str] = mapped_column(String(30))  # debt_load | reserve_fund
    severity: Mapped[str] = mapped_column(String(10), default="warning")
    message: Mapped[str] = mapped_column(Text)
    value: Mapped[float] = mapped_column(Float, default=0.0)
    condition_since: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    resolved_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)


class MlModel(Base):
    """Версия модели автоматической категоризации и показатели ее качества."""

    __tablename__ = "ml_models"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(50), default="categorizer")
    version: Mapped[int] = mapped_column(Integer, default=1)
    status: Mapped[str] = mapped_column(String(10), default="training")  # training|ready|failed
    is_active: Mapped[bool] = mapped_column(Boolean, default=False)
    file_path: Mapped[str] = mapped_column(String(255), default="")
    samples: Mapped[int] = mapped_column(Integer, default=0)
    accuracy: Mapped[float] = mapped_column(Float, default=0.0)
    precision: Mapped[float] = mapped_column(Float, default=0.0)
    recall: Mapped[float] = mapped_column(Float, default=0.0)
    f1: Mapped[float] = mapped_column(Float, default=0.0)
    # Точность на торговых точках, отсутствовавших в обучающем наборе (обобщение)
    unseen_accuracy: Mapped[float] = mapped_column(Float, default=0.0)
    started_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    trained_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    duration_seconds: Mapped[float] = mapped_column(Float, default=0.0)
    started_by: Mapped[str] = mapped_column(String(255), default="system")
    error: Mapped[str] = mapped_column(Text, default="")


class MlTrainingSample(Base):
    """Обезличенная запись для обучения модели: без идентификатора пользователя."""

    __tablename__ = "ml_training_samples"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    text: Mapped[str] = mapped_column(String(300))
    kind: Mapped[str] = mapped_column(String(10), default="expense")
    amount_bucket: Mapped[int] = mapped_column(Integer, default=0)
    category_name: Mapped[str] = mapped_column(String(100), index=True)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)


class QualitySnapshot(Base):
    """Месячный срез показателей качества для дашборда аналитика."""

    __tablename__ = "quality_snapshots"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    month: Mapped[str] = mapped_column(String(7), unique=True)  # ГГГГ-ММ
    accuracy: Mapped[float] = mapped_column(Float, default=0.0)
    precision: Mapped[float] = mapped_column(Float, default=0.0)
    recall: Mapped[float] = mapped_column(Float, default=0.0)
    f1: Mapped[float] = mapped_column(Float, default=0.0)
    forecast_mape: Mapped[float | None] = mapped_column(Float, nullable=True)
    positive_feedback_share: Mapped[float] = mapped_column(Float, default=0.0)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)


# --------------------------------------------------------------------------
# Уведомления и синхронизация
# --------------------------------------------------------------------------
class Notification(Base):
    __tablename__ = "notifications"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), index=True)
    kind: Mapped[str] = mapped_column(String(30))
    title: Mapped[str] = mapped_column(String(200))
    body: Mapped[str] = mapped_column(Text, default="")
    payload: Mapped[dict] = mapped_column(JSON, default=dict)
    important: Mapped[bool] = mapped_column(Boolean, default=False)
    event_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    push_sent_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    email_sent_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    read_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)


class SyncOperation(Base):
    """Примененная операция клиента. Обеспечивает идемпотентность синхронизации."""

    __tablename__ = "sync_operations"

    op_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), index=True)
    applied_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    result: Mapped[str] = mapped_column(String(20), default="applied")


class SyncLog(Base):
    """Журнал синхронизации (п. 4.4.4.2 г)."""

    __tablename__ = "sync_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(String(36), index=True)
    device_id: Mapped[str] = mapped_column(String(36), default="")
    at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, index=True)
    pushed: Mapped[int] = mapped_column(Integer, default=0)
    pulled: Mapped[int] = mapped_column(Integer, default=0)
    conflicts: Mapped[int] = mapped_column(Integer, default=0)
    duplicates: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[str] = mapped_column(String(10), default="ok")
    error: Mapped[str] = mapped_column(Text, default="")


# --------------------------------------------------------------------------
# Администрирование
# --------------------------------------------------------------------------
class AuditLog(Base):
    """Журнал аудита: регистрационный, изменений данных и административных действий."""

    __tablename__ = "audit_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, index=True)
    journal: Mapped[str] = mapped_column(String(10), default="data")  # auth | data | admin
    user_id: Mapped[str | None] = mapped_column(String(36), nullable=True, index=True)
    user_email: Mapped[str] = mapped_column(String(255), default="")
    action: Mapped[str] = mapped_column(String(50), index=True)
    entity: Mapped[str] = mapped_column(String(50), default="")
    entity_id: Mapped[str] = mapped_column(String(64), default="")
    details: Mapped[dict] = mapped_column(JSON, default=dict)
    ip: Mapped[str] = mapped_column(String(45), default="")
    origin: Mapped[str] = mapped_column(String(10), default="system")  # system | import


class ErrorLog(Base):
    """Журнал ошибок клиентской и серверной частей (п. 4.1.5.1)."""

    __tablename__ = "error_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, index=True)
    source: Mapped[str] = mapped_column(String(10), default="server")  # server | client
    level: Mapped[str] = mapped_column(String(10), default="error")  # error | critical
    kind: Mapped[str] = mapped_column(String(100), default="")
    message: Mapped[str] = mapped_column(Text, default="")
    context: Mapped[dict] = mapped_column(JSON, default=dict)


class HealthCheck(Base):
    """Результат периодической проверки работоспособности (п. 4.1.5.2)."""

    __tablename__ = "health_checks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow, index=True)
    database_ok: Mapped[bool] = mapped_column(Boolean, default=True)
    ml_ok: Mapped[bool] = mapped_column(Boolean, default=True)
    rates_ok: Mapped[bool] = mapped_column(Boolean, default=True)
    quotes_ok: Mapped[bool] = mapped_column(Boolean, default=True)
    details: Mapped[dict] = mapped_column(JSON, default=dict)


class RequestStat(Base):
    """Поминутная статистика запросов к программному интерфейсу (мониторинг)."""

    __tablename__ = "request_stats"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    minute: Mapped[datetime] = mapped_column(UtcDateTime, index=True)
    requests: Mapped[int] = mapped_column(Integer, default=0)
    errors: Mapped[int] = mapped_column(Integer, default=0)
    total_ms: Mapped[float] = mapped_column(Float, default=0.0)
    max_ms: Mapped[float] = mapped_column(Float, default=0.0)


class SystemSetting(Base):
    __tablename__ = "system_settings"

    key: Mapped[str] = mapped_column(String(100), primary_key=True)
    value: Mapped[dict] = mapped_column(JSON, default=dict)
    updated_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    updated_by: Mapped[str] = mapped_column(String(255), default="")


class SupportRequest(Base):
    __tablename__ = "support_requests"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id"), index=True)
    subject: Mapped[str] = mapped_column(String(200))
    message: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(10), default="open")
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)


class BackgroundJob(Base):
    """Фоновая задача (переобучение модели, экспорт и т.п.) и ее состояние."""

    __tablename__ = "background_jobs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(50), index=True)
    args: Mapped[dict] = mapped_column(JSON, default=dict)
    status: Mapped[str] = mapped_column(String(10), default="queued")  # queued|running|done|failed
    result: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(UtcDateTime, default=utcnow)
    started_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(UtcDateTime, nullable=True)


SYNC_ENTITIES: dict[str, type] = {
    "account": Account,
    "category": Category,
    "transaction": Transaction,
    "template": TransactionTemplate,
    "recurring_payment": RecurringPayment,
    "budget": Budget,
    "goal": Goal,
    "goal_contribution": GoalContribution,
    "investment_trade": InvestmentTrade,
}
