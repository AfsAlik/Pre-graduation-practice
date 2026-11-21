"""Параметры конфигурации серверной части.

Все параметры читаются из переменных окружения (или файла .env) с префиксом FH_.
Назначение каждого параметра описано в руководстве администратора.
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="FH_", env_file=".env", extra="ignore")

    # --- Общие параметры ---
    app_name: str = "Финансовый помощник"
    environment: str = "dev"  # dev | test | prod
    debug: bool = False

    # --- База данных и кеш ---
    database_url: str = "postgresql+psycopg://finhelper:finhelper@localhost:5432/finhelper"
    db_pool_size: int = 10
    db_max_overflow: int = 10
    redis_url: str = ""  # пустая строка – кеш в памяти процесса, задачи выполняются в потоке

    # --- Безопасность (п. 4.4.4, 4.4.7 ТЗ) ---
    secret_key: str = "change-me-in-production"
    access_token_minutes: int = 30
    refresh_token_days: int = 30
    max_failed_logins: int = 5
    lockout_minutes: int = 15
    password_reset_minutes: int = 30
    email_confirm_hours: int = 48
    require_email_confirmation: bool = True

    # --- Сервис машинного обучения ---
    ml_service_url: str = ""  # пустая строка – вызовы выполняются внутри процесса
    ml_models_dir: str = "var/models"
    ml_min_accuracy: float = 0.80

    # --- Внешние источники данных ---
    rates_provider: str = "cbr"  # cbr | static
    rates_url: str = "https://www.cbr.ru/scripts/XML_daily.asp"
    quotes_provider: str = "moex"  # moex | static
    quotes_url: str = "https://iss.moex.com/iss"
    external_timeout_seconds: float = 8.0
    rates_ttl_hours: int = 24

    # --- Уведомления ---
    push_provider: str = "log"  # log | fcm
    fcm_credentials_file: str = ""
    email_provider: str = "log"  # log | smtp
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_from: str = "noreply@finhelper.local"
    public_base_url: str = "http://localhost:8000"

    # --- Хранилище файлов ---
    storage_provider: str = "local"  # local | s3
    storage_dir: str = "var/storage"
    s3_endpoint: str = ""
    s3_bucket: str = "finhelper"
    s3_access_key: str = ""
    s3_secret_key: str = ""

    # --- Значения по умолчанию для пользователей и системы ---
    default_base_currency: str = "RUB"
    budget_warning_percent: int = 80
    deleted_retention_days: int = 30
    backup_cron: str = "0 3 * * *"


@lru_cache
def get_settings() -> Settings:
    return Settings()
