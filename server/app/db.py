"""Подключение к базе данных и управление сеансами SQLAlchemy."""
from collections.abc import Iterator

from sqlalchemy import create_engine, event, func
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import get_settings


class Base(DeclarativeBase):
    """Базовый класс всех моделей данных."""


def _make_engine(url: str):
    kwargs: dict = {"pool_pre_ping": True, "future": True}
    if url.startswith("sqlite"):
        from sqlalchemy.pool import StaticPool

        kwargs["connect_args"] = {"check_same_thread": False}
        if ":memory:" in url:
            kwargs["poolclass"] = StaticPool
    else:
        # Число соединений одного процесса; суммарно по всем процессам серверного приложения
        # и исполнителя фоновых задач оно не должно превышать max_connections PostgreSQL
        settings = get_settings()
        kwargs["pool_size"] = settings.db_pool_size
        kwargs["max_overflow"] = settings.db_max_overflow
    return create_engine(url, **kwargs)


engine = _make_engine(get_settings().database_url)

if engine.dialect.name == "sqlite":

    @event.listens_for(engine, "connect")
    def _sqlite_unicode_lower(dbapi_connection, _record) -> None:
        # Встроенная функция lower() SQLite не преобразует кириллицу
        dbapi_connection.create_function(
            "lower", 1, lambda value: value.lower() if isinstance(value, str) else value
        )


def contains(column, text: str):
    """Условие поиска подстроки без учета регистра (в том числе для кириллицы)."""
    escaped = text.strip().lower().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return func.lower(column).like(f"%{escaped}%", escape="\\")
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def get_db() -> Iterator[Session]:
    """Зависимость FastAPI: сеанс БД на время обработки запроса.

    Все изменения одного запроса фиксируются одной транзакцией (п. 4.4.8.3 ТЗ):
    либо применяются целиком, либо откатываются.
    """
    db = SessionLocal()
    try:
        yield db
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()
