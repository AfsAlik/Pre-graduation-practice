"""Подготовка системы к работе: схема БД, справочники, начальная модель, администратор."""
import logging

from sqlalchemy.orm import Session

from .config import get_settings
from .db import Base, SessionLocal, engine
from .models import User, utcnow
from .schemas import NotificationSettings
from .security import hash_password
from .services import ml_registry
from .services.categories import seed_system_categories
from .services.currency import refresh_rates

log = logging.getLogger(__name__)


def create_user(db: Session, email: str, password: str, role: str = "user",
                display_name: str = "") -> User:
    """Создает подтвержденную учетную запись (используется при развертывании и в тестах)."""
    user = db.query(User).filter(User.email == email.lower()).first()
    if user is None:
        user = User(email=email.lower(), display_name=display_name, role=role,
                    password_hash=hash_password(password), email_confirmed=True,
                    consent_pd_at=utcnow(),
                    base_currency=get_settings().default_base_currency,
                    notification_settings=NotificationSettings().model_dump())
        db.add(user)
    else:
        user.role, user.password_hash = role, hash_password(password)
    db.flush()
    return user


def init_db(*, create_tables: bool = True, train_model: bool = True,
            load_rates: bool = True) -> None:
    """Создает таблицы (при необходимости), заполняет справочники и обучает модель."""
    if create_tables:
        Base.metadata.create_all(engine)
    db = SessionLocal()
    try:
        created = seed_system_categories(db)
        if created:
            log.info("Добавлено системных категорий: %d", created)
        db.commit()
        if train_model:
            model = ml_registry.ensure_active_model(db)
            db.commit()
            log.info("Активная модель категоризации: версия %s, accuracy %.3f",
                     model.version, model.accuracy)
        if load_rates:
            result = refresh_rates(db)
            db.commit()
            log.info("Курсы валют: %s", result)
    finally:
        db.close()
