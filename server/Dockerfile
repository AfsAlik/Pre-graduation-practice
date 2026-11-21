# Образ серверной части «Финансового помощника»: серверное приложение,
# исполнитель фоновых задач и сервис машинного обучения (команда задается в compose).
FROM python:3.12-slim-trixie

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1

# Шрифт с кириллицей для экспорта в PDF и утилиты PostgreSQL для резервного копирования
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-dejavu-core postgresql-client curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv/finhelper
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY alembic.ini ./
COPY alembic ./alembic
COPY app ./app
COPY ml_service ./ml_service
COPY scripts ./scripts
RUN chmod +x scripts/*.sh && useradd --create-home finhelper \
    && sed -i 's/\r$//' scripts/*.sh \
    && mkdir -p var/models var/storage var/backups && chown -R finhelper:finhelper /srv/finhelper
USER finhelper

EXPOSE 8000
CMD ["scripts/start-api.sh"]
