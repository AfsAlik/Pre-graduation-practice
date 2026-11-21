"""Серверное приложение «Финансовый помощник»: точка входа FastAPI.

Запуск для разработки: uvicorn app.main:app --reload
Документация программного интерфейса (OpenAPI): /docs и /openapi.json
"""
import logging
import os
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from sqlalchemy import text

from . import bootstrap
from .audit import log_error
from .config import get_settings
from .db import SessionLocal
from .routers import admin, analytics, auth, finance, insights, ml, profile, sync
from .services import monitoring, settings_store
from .services.scheduler import Scheduler

log = logging.getLogger("app")
API_PREFIX = "/api/v1"
_maintenance = {"at": 0.0, "enabled": False, "message": ""}
# Запросы, доступные в режиме технического обслуживания
_MAINTENANCE_ALLOWED = ("/health", "/metrics", f"{API_PREFIX}/auth", f"{API_PREFIX}/admin",
                        f"{API_PREFIX}/system", f"{API_PREFIX}/profile", "/docs", "/openapi.json")


def _flag(name: str, default: str) -> bool:
    return os.getenv(name, default).lower() in ("1", "true", "yes")


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings = get_settings()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    scheduler = None
    if settings.environment != "test":
        bootstrap.init_db(create_tables=_flag("FH_AUTO_CREATE_TABLES", "true"))
        # Без Redis периодические задачи выполняет поток серверного приложения
        if _flag("FH_RUN_SCHEDULER", "false" if settings.redis_url else "true"):
            scheduler = Scheduler()
            scheduler.start_thread()
    yield
    if scheduler is not None:
        scheduler.stop()
    monitoring.flush_now()


app = FastAPI(
    title="Финансовый помощник – программный интерфейс",
    description="REST API мобильного приложения для управления личными финансами.",
    version="1.0.0",
    lifespan=lifespan,
)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


def maintenance_state() -> dict:
    """Состояние режима технического обслуживания (кешируется на 15 секунд)."""
    now = time.monotonic()
    if now - _maintenance["at"] > 15:
        db = SessionLocal()
        try:
            value = settings_store.get_value(db, "maintenance")
            _maintenance.update(at=now, enabled=bool(value.get("enabled")),
                                message=value.get("message", ""))
        except Exception:
            _maintenance.update(at=now)
        finally:
            db.close()
    return _maintenance


@app.middleware("http")
async def observe(request: Request, call_next):
    """Учет времени ответа, журнал ошибок и режим технического обслуживания."""
    started = time.monotonic()
    path = request.url.path
    if path.startswith(API_PREFIX) and not path.startswith(_MAINTENANCE_ALLOWED):
        state = maintenance_state()
        if state["enabled"]:
            return JSONResponse(
                {"detail": state["message"] or "Выполняются технические работы. Функции "
                 "автономного режима доступны, синхронизация временно приостановлена."},
                status_code=503, headers={"Retry-After": "600"})
    try:
        response = await call_next(request)
        status = response.status_code
    except Exception as exc:
        log.exception("Необработанная ошибка запроса %s %s", request.method, path)
        db = SessionLocal()
        try:
            log_error(db, str(exc), kind=type(exc).__name__, level="critical",
                      context={"method": request.method, "path": path})
            db.commit()
        except Exception:
            db.rollback()
        finally:
            db.close()
        response = JSONResponse({"detail": "Внутренняя ошибка сервера"}, status_code=500)
        status = 500
    route = request.scope.get("route")
    template = getattr(route, "path", path) if route else "unmatched"
    if not path.startswith(("/metrics", "/health")):
        monitoring.record_request(request.method, template, status, time.monotonic() - started)
    return response


for module in (auth, profile, finance, analytics, insights, sync, admin, ml):
    app.include_router(module.router, prefix=API_PREFIX)


@app.get("/health", tags=["Служебные"])
def health() -> dict:
    """Проверка доступности серверного приложения."""
    return {"status": "ok"}


@app.get("/health/ready", tags=["Служебные"])
def ready():
    """Проверка готовности: доступность базы данных."""
    db = SessionLocal()
    try:
        db.execute(text("SELECT 1"))
        return {"status": "ok", "database": True}
    except Exception as exc:
        return JSONResponse({"status": "error", "database": False, "detail": str(exc)},
                            status_code=503)
    finally:
        db.close()


@app.get("/metrics", tags=["Служебные"], include_in_schema=False)
def metrics() -> Response:
    """Метрики в формате Prometheus."""
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
