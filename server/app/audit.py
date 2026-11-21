"""Ведение журналов аудита и ошибок (п. 4.4.4.2 ТЗ)."""
from typing import Any

from fastapi import Request
from sqlalchemy.orm import Session

from .models import AuditLog, ErrorLog, User

JOURNAL_AUTH = "auth"  # регистрационный журнал: вход, выход, неудачные попытки
JOURNAL_DATA = "data"  # журнал изменений финансовых данных
JOURNAL_ADMIN = "admin"  # журнал административных действий


def client_ip(request: Request | None) -> str:
    if request is None or request.client is None:
        return ""
    return request.headers.get("x-forwarded-for", request.client.host).split(",")[0].strip()


def audit(
    db: Session,
    action: str,
    *,
    user: User | None = None,
    email: str = "",
    journal: str = JOURNAL_DATA,
    entity: str = "",
    entity_id: str = "",
    details: dict[str, Any] | None = None,
    request: Request | None = None,
) -> None:
    db.add(
        AuditLog(
            journal=journal,
            user_id=user.id if user else None,
            user_email=user.email if user else email,
            action=action,
            entity=entity,
            entity_id=entity_id,
            details=details or {},
            ip=client_ip(request),
        )
    )


def log_error(
    db: Session,
    message: str,
    *,
    kind: str = "",
    source: str = "server",
    level: str = "error",
    context: dict[str, Any] | None = None,
) -> None:
    db.add(ErrorLog(source=source, level=level, kind=kind, message=message, context=context or {}))
