"""Системные настройки, изменяемые администратором (п. 4.2.1.8 и ТЗ)."""
from sqlalchemy.orm import Session

from ..config import get_settings
from ..models import SystemSetting, utcnow


def defaults() -> dict:
    settings = get_settings()
    return {
        "integrations": {
            "rates_provider": settings.rates_provider,
            "quotes_provider": settings.quotes_provider,
            "push_provider": settings.push_provider,
            "email_provider": settings.email_provider,
        },
        "notifications": {"budget_warning_percent": settings.budget_warning_percent},
        "backup": {"cron": settings.backup_cron, "keep_days": 92, "directory": "var/backups"},
        "maintenance": {"enabled": False, "message": ""},
    }


def get_all(db: Session) -> dict:
    result = defaults()
    for row in db.query(SystemSetting).all():
        if isinstance(result.get(row.key), dict) and isinstance(row.value, dict):
            result[row.key] = {**result[row.key], **row.value}
        else:
            result[row.key] = row.value
    return result


def get_value(db: Session, key: str) -> dict:
    return get_all(db).get(key, {})


def integration(db: Session, name: str) -> str:
    """Действующее значение параметра интеграции: настройка администратора или параметр запуска."""
    return str(get_value(db, "integrations").get(name) or getattr(get_settings(), name))


def set_value(db: Session, key: str, value: dict, updated_by: str) -> None:
    row = db.get(SystemSetting, key)
    if row is None:
        db.add(SystemSetting(key=key, value=value, updated_by=updated_by))
    else:
        row.value, row.updated_at, row.updated_by = value, utcnow(), updated_by
    db.flush()
