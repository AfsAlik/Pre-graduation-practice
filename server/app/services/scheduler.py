"""Периодические задачи серверной части.

Расписание:
  - каждую минуту  – проверка работоспособности компонентов (п. 4.1.5.2 ТЗ);
  - каждый час     – напоминания о регулярных платежах;
  - раз в сутки    – обновление курсов валют и котировок (п. 4.2.2.15 ТЗ), оценка
                     финансовых рисков (п. 4.2.2.14 ТЗ), формирование рекомендаций,
                     окончательное удаление данных с истекшим сроком хранения.
Задачи выполняются процессом app.worker либо потоком серверного приложения
(режим разработки без Redis).
"""
import logging
import threading
import time
from datetime import date, timedelta

from sqlalchemy.orm import Session

from ..config import get_settings
from ..db import SessionLocal
from ..models import (
    SYNC_ENTITIES,
    AuthToken,
    HealthCheck,
    RecurringPayment,
    RequestStat,
    SyncOperation,
    User,
    utcnow,
)
from . import insights, ml_registry, monitoring
from .categories import SYSTEM_USER_ID
from .currency import refresh_rates
from .notifier import notify
from .periods import add_months
from .quotes import refresh_user_quotes
from .tasks import task

log = logging.getLogger(__name__)


def active_users(db: Session) -> list[User]:
    return db.query(User).filter(
        User.id != SYSTEM_USER_ID, User.deleted_at.is_(None), User.is_blocked.is_(False)).all()


def remind_recurring_payments(db: Session, today: date | None = None) -> int:
    """Формирует напоминания о предстоящих регулярных платежах и сдвигает дату платежа."""
    today = today or date.today()
    sent = 0
    payments = db.query(RecurringPayment).filter(
        RecurringPayment.deleted_at.is_(None), RecurringPayment.is_active.is_(True)).all()
    for payment in payments:
        user = db.get(User, payment.user_id)
        if user is None or user.deleted_at is not None:
            continue
        days_left = (payment.next_date - today).days
        if 0 <= days_left <= payment.remind_days_before and \
                payment.last_reminded_for != payment.next_date:
            when = "сегодня" if days_left == 0 else f"через {days_left} дн."
            notify(db, user, "recurring_payment", f"Предстоящий платеж: {payment.name}",
                   f"{payment.amount} {payment.currency} – {when} "
                   f"({payment.next_date.strftime('%d.%m.%Y')}).",
                   payload={"recurring_payment_id": payment.id})
            payment.last_reminded_for = payment.next_date
            sent += 1
        if days_left < 0:  # дата прошла: переносится на следующий период
            while payment.next_date < today:
                if payment.period == "week":
                    payment.next_date += timedelta(days=7)
                elif payment.period == "year":
                    payment.next_date = add_months(payment.next_date, 12)
                else:
                    payment.next_date = add_months(payment.next_date, 1)
            from .revisions import touch

            touch(db, payment)
    return sent


def daily_user_analysis(db: Session) -> float | None:
    """Оценка рисков и формирование рекомендаций для всех пользователей.

    Возвращает среднюю по пользователям ошибку прогноза расходов (MAPE, %),
    рассчитанную проверкой прогноза на последних месяцах истории.
    """
    errors: list[float] = []
    for user in active_users(db):
        try:
            insights.assess_risks(db, user)
            insights.generate_recommendations(db, user)
            mape = insights.forecast(db, user)["expense"]["mape"]
            if mape is not None:
                errors.append(mape)
            db.commit()
        except Exception:
            db.rollback()
            log.exception("Ошибка анализа данных пользователя %s", user.id)
    return round(sum(errors) / len(errors), 2) if errors else None


def purge_expired(db: Session) -> dict:
    """Окончательное удаление данных, срок хранения которых истек (п. 4.3.2.6 ТЗ)."""
    settings = get_settings()
    border = utcnow() - timedelta(days=settings.deleted_retention_days)
    removed = {}
    for name, model in SYNC_ENTITIES.items():
        removed[name] = db.query(model).filter(
            model.deleted_at.is_not(None), model.deleted_at < border).delete()
    for user in db.query(User).filter(User.deleted_at.is_not(None), User.deleted_at < border):
        for model in SYNC_ENTITIES.values():
            db.query(model).filter(model.user_id == user.id).delete()
        user.email = f"deleted-{user.id}@finhelper.local"
        user.display_name, user.password_hash, user.totp_secret = "", "!", None
        user.is_blocked = True
    db.query(AuthToken).filter(AuthToken.expires_at < utcnow() - timedelta(days=7)).delete()
    db.query(SyncOperation).filter(SyncOperation.applied_at < utcnow() - timedelta(days=60)).delete()
    db.query(RequestStat).filter(RequestStat.minute < utcnow() - timedelta(days=92)).delete()
    db.query(HealthCheck).filter(HealthCheck.at < utcnow() - timedelta(days=92)).delete()
    return removed


def daily(db: Session) -> None:
    refresh_rates(db)
    db.commit()
    refresh_user_quotes(db, force=True)
    db.commit()
    forecast_error = daily_user_analysis(db)
    purge_expired(db)
    ml_registry.save_snapshot(db, forecast_error)


_last_backup_day: date | None = None


def backup_if_due(db: Session) -> None:
    """Создает резервную копию БД по расписанию из системных настроек.

    Расписание задается в формате cron; учитываются минуты и часы (время UTC),
    копия создается один раз в сутки. Копии старше срока хранения удаляются.
    """
    global _last_backup_day
    from . import settings_store

    if not get_settings().database_url.startswith("postgresql"):
        return
    schedule = settings_store.get_value(db, "backup")
    try:
        minute, hour = (int(part) for part in str(schedule.get("cron", "")).split()[:2])
    except ValueError:
        return
    now = utcnow()
    if _last_backup_day == now.date() or (now.hour, now.minute) < (hour, minute):
        return
    _last_backup_day = now.date()
    from .. import manage

    directory = str(schedule.get("directory", "var/backups"))
    manage.backup(directory)
    manage.remove_old_backups(directory, int(schedule.get("keep_days", 92)))


JOBS = [
    ("backup", 60, backup_if_due),
    ("health_check", 60, monitoring.run_health_check),
    ("recurring_reminders", 3600, remind_recurring_payments),
    ("daily", 24 * 3600, daily),
]


@task("run_job")
def run_job(db: Session, job: str) -> None:
    """Фоновая задача: выполнение периодической задачи по запросу администратора."""
    for job_name, _interval, func in JOBS:
        if job_name == job:
            func(db)


class Scheduler:
    """Простой планировщик: запускает задачи через заданные интервалы."""

    def __init__(self) -> None:
        self._last: dict[str, float] = {}
        self._stop = threading.Event()

    def tick(self) -> None:
        now = time.monotonic()
        for name, interval, func in JOBS:
            if now - self._last.get(name, -1e12) < interval:
                continue
            self._last[name] = now
            db = SessionLocal()
            try:
                func(db)
                db.commit()
            except Exception:
                db.rollback()
                log.exception("Ошибка периодической задачи %s", name)
            finally:
                db.close()

    def run_forever(self, pause: float = 5.0) -> None:
        while not self._stop.is_set():
            self.tick()
            self._stop.wait(pause)

    def start_thread(self) -> threading.Thread:
        thread = threading.Thread(target=self.run_forever, name="fh-scheduler", daemon=True)
        thread.start()
        return thread

    def stop(self) -> None:
        self._stop.set()
