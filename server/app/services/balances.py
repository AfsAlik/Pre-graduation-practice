"""Расчет балансов счетов пользователя."""
from decimal import Decimal

from sqlalchemy import case, func
from sqlalchemy.orm import Session

from ..models import Account, Transaction, User
from .currency import safe_convert

ZERO = Decimal("0")


def account_balances(db: Session, user_id: str) -> dict[str, Decimal]:
    """Балансы всех счетов: начальный остаток плюс обороты по операциям."""
    accounts = (
        db.query(Account).filter(Account.user_id == user_id, Account.deleted_at.is_(None)).all()
    )
    balances = {a.id: Decimal(a.initial_balance) for a in accounts}
    signed = case(
        (Transaction.kind == "income", Transaction.amount), else_=-Transaction.amount
    )
    rows = (
        db.query(Transaction.account_id, func.coalesce(func.sum(signed), 0))
        .filter(Transaction.user_id == user_id, Transaction.deleted_at.is_(None))
        .group_by(Transaction.account_id)
        .all()
    )
    for account_id, total in rows:
        if account_id in balances:
            balances[account_id] += Decimal(total)
    incoming = (
        db.query(
            Transaction.to_account_id,
            func.coalesce(func.sum(func.coalesce(Transaction.amount_to, Transaction.amount)), 0),
        )
        .filter(
            Transaction.user_id == user_id,
            Transaction.deleted_at.is_(None),
            Transaction.kind == "transfer",
        )
        .group_by(Transaction.to_account_id)
        .all()
    )
    for account_id, total in incoming:
        if account_id in balances:
            balances[account_id] += Decimal(total)
    return balances


def total_balance(db: Session, user: User, *, liquid_only: bool = False) -> Decimal:
    """Общий баланс в базовой валюте пользователя."""
    balances = account_balances(db, user.id)
    total = ZERO
    for account in db.query(Account).filter(
        Account.user_id == user.id, Account.deleted_at.is_(None), Account.is_archived.is_(False)
    ):
        if liquid_only and account.type not in ("cash", "bank"):
            continue
        total += safe_convert(
            db, balances.get(account.id, ZERO), account.currency, user.base_currency
        )
    return total
