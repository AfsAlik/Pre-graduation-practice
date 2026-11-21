"""Очередь фоновых задач (п. 4.1.2.2 ТЗ).

Длительные операции (доставка уведомлений, повторное обучение модели, обновление
котировок) выполняются вне обработки запроса. При заданном FH_REDIS_URL задачи
помещаются в очередь Redis и выполняются отдельным процессом app.worker; без
Redis – в пуле потоков текущего процесса (режим разработки и тестов).

Задача ставится в очередь только после фиксации транзакции запроса, чтобы
исполнитель видел сохраненные данные.
"""
import json
import logging
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor

from sqlalchemy import event
from sqlalchemy.orm import Session

from ..config import get_settings
from ..db import SessionLocal

log = logging.getLogger(__name__)

QUEUE_KEY = "fh:tasks"
_registry: dict[str, Callable[..., None]] = {}
_pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="fh-task")
_redis_client = None
# В тестах задачи выполняются синхронно сразу после фиксации транзакции
run_inline = False
# Задачи, которые не выполняются (например, обращения к внешним источникам при загрузке данных)
skipped: set[str] = set()


def task(name: str):
    """Регистрирует функцию как фоновую задачу. Первый аргумент функции – сеанс БД."""

    def decorator(func: Callable[..., None]):
        _registry[name] = func
        return func

    return decorator


def get_redis():
    global _redis_client
    url = get_settings().redis_url
    if not url:
        return None
    if _redis_client is None:
        import redis

        _redis_client = redis.Redis.from_url(url, decode_responses=True)
    return _redis_client


def run_task(name: str, kwargs: dict) -> None:
    """Выполняет задачу в отдельном сеансе БД."""
    func = _registry.get(name)
    if func is None:
        log.error("Неизвестная фоновая задача: %s", name)
        return
    db = SessionLocal()
    try:
        func(db, **kwargs)
        db.commit()
    except Exception:
        db.rollback()
        log.exception("Ошибка выполнения фоновой задачи %s", name)
    finally:
        db.close()


def _dispatch(name: str, kwargs: dict) -> None:
    if name in skipped:
        return
    if run_inline:
        run_task(name, kwargs)
        return
    client = get_redis()
    if client is not None:
        try:
            client.rpush(QUEUE_KEY, json.dumps({"name": name, "kwargs": kwargs}))
            return
        except Exception:
            log.exception("Очередь Redis недоступна, задача %s выполняется в потоке", name)
    _pool.submit(run_task, name, kwargs)


def enqueue(db: Session, name: str, **kwargs) -> None:
    """Ставит задачу в очередь после успешной фиксации текущей транзакции."""
    db.info.setdefault("pending_tasks", []).append((name, kwargs))


@event.listens_for(SessionLocal, "after_commit")
def _after_commit(session: Session) -> None:
    pending = session.info.pop("pending_tasks", [])
    for name, kwargs in pending:
        _dispatch(name, kwargs)


@event.listens_for(SessionLocal, "after_rollback")
def _after_rollback(session: Session) -> None:
    session.info.pop("pending_tasks", None)
