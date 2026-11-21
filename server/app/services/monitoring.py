"""Мониторинг и диагностирование системы (п. 4.1.5 ТЗ).

Модуль собирает метрики запросов (для Prometheus и раздела «Мониторинг»
мобильного приложения) и выполняет периодическую проверку работоспособности
базы данных, сервиса машинного обучения и внешних интеграций.
"""
import logging
import threading
import time
from datetime import UTC, datetime, timedelta

from prometheus_client import Counter, Gauge, Histogram
from sqlalchemy import func, text
from sqlalchemy.orm import Session

from ..db import SessionLocal
from ..ml import client as ml_client
from ..models import ErrorLog, HealthCheck, RequestStat, utcnow
from .currency import rates_info

log = logging.getLogger(__name__)

REQUESTS = Counter("fh_http_requests_total", "Число HTTP-запросов", ["method", "path", "status"])
LATENCY = Histogram(
    "fh_http_request_duration_seconds", "Время обработки HTTP-запроса", ["method", "path"],
    buckets=(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10),
)
COMPONENT_UP = Gauge("fh_component_up", "Работоспособность компонента (1 – да)", ["component"])

_lock = threading.Lock()
_bucket = {"minute": None, "requests": 0, "errors": 0, "total_ms": 0.0, "max_ms": 0.0}


def _flush(bucket: dict) -> None:
    if not bucket["minute"] or not bucket["requests"]:
        return
    db = SessionLocal()
    try:
        db.add(RequestStat(minute=bucket["minute"], requests=bucket["requests"],
                           errors=bucket["errors"], total_ms=bucket["total_ms"],
                           max_ms=bucket["max_ms"]))
        db.commit()
    except Exception:
        db.rollback()
        log.exception("Не удалось сохранить статистику запросов")
    finally:
        db.close()


def record_request(method: str, path: str, status: int, seconds: float) -> None:
    """Учитывает обработанный запрос в метриках и поминутной статистике."""
    REQUESTS.labels(method, path, str(status)).inc()
    LATENCY.labels(method, path).observe(seconds)
    minute = datetime.now(UTC).replace(second=0, microsecond=0)
    to_flush = None
    with _lock:
        if _bucket["minute"] != minute:
            to_flush = dict(_bucket)
            _bucket.update(minute=minute, requests=0, errors=0, total_ms=0.0, max_ms=0.0)
        _bucket["requests"] += 1
        _bucket["errors"] += 1 if status >= 500 else 0
        _bucket["total_ms"] += seconds * 1000
        _bucket["max_ms"] = max(_bucket["max_ms"], seconds * 1000)
    if to_flush:
        _flush(to_flush)


def flush_now() -> None:
    with _lock:
        to_flush = dict(_bucket)
        _bucket.update(minute=None, requests=0, errors=0, total_ms=0.0, max_ms=0.0)
    _flush(to_flush)


def run_health_check(db: Session) -> HealthCheck:
    """Проверяет компоненты системы и сохраняет результат."""
    started = time.monotonic()
    details: dict = {}
    try:
        db.execute(text("SELECT 1"))
        database_ok = True
    except Exception as exc:
        database_ok, details["database"] = False, str(exc)
    ml_ok = ml_client.health()
    info = rates_info(db) if database_ok else {"stale": True, "as_of": None}
    rates_ok = not info["stale"]
    details["rates_as_of"] = info["as_of"]
    details["check_ms"] = round((time.monotonic() - started) * 1000, 1)
    check = HealthCheck(database_ok=database_ok, ml_ok=ml_ok, rates_ok=rates_ok,
                        quotes_ok=True, details=details)
    for name, ok in (("database", database_ok), ("ml", ml_ok), ("rates", rates_ok)):
        COMPONENT_UP.labels(name).set(1 if ok else 0)
    if database_ok:
        db.add(check)
        db.flush()
    return check


def summary(db: Session, hours: int = 24) -> dict:
    """Сводка для раздела «Мониторинг»: доступность, время ответа, ошибки, график."""
    flush_now()
    since = utcnow() - timedelta(hours=hours)
    requests, errors, total_ms, max_ms = db.query(
        func.coalesce(func.sum(RequestStat.requests), 0),
        func.coalesce(func.sum(RequestStat.errors), 0),
        func.coalesce(func.sum(RequestStat.total_ms), 0.0),
        func.coalesce(func.max(RequestStat.max_ms), 0.0),
    ).filter(RequestStat.minute >= since).one()
    checks = db.query(HealthCheck).filter(HealthCheck.at >= since).order_by(HealthCheck.at).all()

    def ok(check: HealthCheck) -> bool:
        return check.database_ok and check.ml_ok

    availability = (sum(1 for c in checks if ok(c)) / len(checks) * 100) if checks else 100.0
    chart: dict[str, list[int]] = {}
    for check in checks:
        key = check.at.strftime("%Y-%m-%d %H:00")
        chart.setdefault(key, [0, 0])
        chart[key][0] += 1
        chart[key][1] += 1 if ok(check) else 0
    last = checks[-1] if checks else None
    logged_errors = db.query(func.count(ErrorLog.id)).filter(ErrorLog.at >= since).scalar() or 0
    return {
        "period_hours": hours,
        "availability_percent": round(availability, 2),
        "requests": int(requests),
        "server_errors": int(errors),
        "logged_errors": int(logged_errors),
        "average_response_ms": round(total_ms / requests, 1) if requests else 0.0,
        "max_response_ms": round(max_ms, 1),
        "components": {
            "database": last.database_ok if last else True,
            "ml_service": last.ml_ok if last else True,
            "rates": last.rates_ok if last else False,
        },
        "last_check_at": last.at.isoformat() if last else None,
        "availability_chart": [
            {"hour": hour, "percent": round(good / total * 100, 1)}
            for hour, (total, good) in sorted(chart.items())
        ],
    }
