#!/bin/sh
# Запуск серверного приложения: миграции схемы БД, подготовка справочников, веб-сервер.
set -e
alembic upgrade head
FH_AUTO_CREATE_TABLES=false python -m app.manage init
exec gunicorn app.main:app \
    --worker-class uvicorn.workers.UvicornWorker \
    --workers "${FH_WEB_WORKERS:-2}" \
    --bind 0.0.0.0:8000 \
    --access-logfile - \
    --timeout 120
