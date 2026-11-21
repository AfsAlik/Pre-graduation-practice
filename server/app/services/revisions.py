"""Ревизии данных пользователя для синхронизации.

Каждое изменение синхронизируемой сущности получает очередной номер ревизии
пользователя. Счетчик увеличивается атомарно одной командой UPDATE, поэтому
параллельные изменения с разных устройств не получают одинаковых номеров, а
мобильное приложение запрашивает только записи с номером больше известного ему.
"""
from datetime import datetime

from sqlalchemy import update
from sqlalchemy.orm import Session

from ..models import User, utcnow


def next_rev(db: Session, user_id: str) -> int:
    return db.execute(
        update(User)
        .where(User.id == user_id)
        .values(sync_rev=User.sync_rev + 1)
        .returning(User.sync_rev)
    ).scalar_one()


def touch(db: Session, obj, *, updated_at: datetime | None = None) -> None:
    """Отмечает изменение сущности: время изменения и новая ревизия."""
    obj.updated_at = updated_at or utcnow()
    obj.rev = next_rev(db, obj.user_id)
