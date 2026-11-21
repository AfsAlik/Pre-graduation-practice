"""Внутренний программный интерфейс сервиса машинного обучения (п. 4.1.2.2 ТЗ).

Если задан параметр FH_ML_SERVICE_URL, вызовы передаются отдельному сервису
(ml_service) по HTTP; иначе те же функции выполняются внутри процесса –
этот режим используется при разработке и в автоматических тестах.
В сервис передаются только обезличенные данные: тексты операций и числовые ряды.
"""
import httpx

from ..config import get_settings
from . import categorizer, forecast


def _remote() -> str:
    return get_settings().ml_service_url.rstrip("/")


def _post(path: str, payload: dict, timeout: float = 30.0) -> dict:
    response = httpx.post(f"{_remote()}{path}", json=payload, timeout=timeout)
    response.raise_for_status()
    return response.json()


def train(samples: list[dict], version: int) -> dict:
    if _remote():
        return _post("/train", {"samples": samples, "version": version}, timeout=7200.0)
    return categorizer.train(samples, get_settings().ml_models_dir, version)


def predict(version: int, items: list[dict]) -> list[dict]:
    if not items:
        return []
    if _remote():
        return _post("/categorize", {"version": version, "items": items})["items"]
    path = categorizer.model_path(get_settings().ml_models_dir, version)
    return categorizer.predict(path, items)


def evaluate(version: int, samples: list[dict]) -> dict:
    if _remote():
        return _post("/evaluate", {"version": version, "samples": samples})
    path = categorizer.model_path(get_settings().ml_models_dir, version)
    return categorizer.evaluate(path, samples)


def forecast_next(values: list[float]) -> dict:
    if _remote():
        return _post("/forecast", {"values": values})
    return forecast.forecast_next(values)


def health() -> bool:
    if not _remote():
        return True
    try:
        return httpx.get(f"{_remote()}/health", timeout=3.0).status_code == 200
    except Exception:
        return False
