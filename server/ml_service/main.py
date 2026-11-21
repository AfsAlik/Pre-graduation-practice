"""Сервис машинного обучения «Финансового помощника».

Отдельное приложение с внутренним программным интерфейсом: обучение и
применение модели категоризации, прогнозирование временных рядов. Сервис не
имеет доступа к базе данных и получает только обезличенные данные.

Запуск: uvicorn ml_service.main:app --port 8001
"""
import os

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.ml import categorizer, forecast

MODELS_DIR = os.getenv("FH_ML_MODELS_DIR", "var/models")
app = FastAPI(title="Финансовый помощник – сервис машинного обучения", version="1.0.0")


class Sample(BaseModel):
    text: str
    kind: str = "expense"
    category: str
    # Исходная строка, вариацией которой является пример (учитывается при оценке качества)
    group: str | None = None


class Item(BaseModel):
    text: str
    kind: str = "expense"


class TrainRequest(BaseModel):
    samples: list[Sample] = Field(min_length=10)
    version: int = Field(ge=1)


class CategorizeRequest(BaseModel):
    version: int
    items: list[Item]


class EvaluateRequest(BaseModel):
    version: int
    samples: list[Sample]


class ForecastRequest(BaseModel):
    values: list[float]


def _path(version: int) -> str:
    path = categorizer.model_path(MODELS_DIR, version)
    if not os.path.exists(path):
        raise HTTPException(404, f"Модель версии {version} не найдена")
    return path


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/train")
def train(request: TrainRequest) -> dict:
    samples = [s.model_dump() for s in request.samples]
    try:
        return categorizer.train(samples, MODELS_DIR, request.version)
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from exc


@app.post("/categorize")
def categorize(request: CategorizeRequest) -> dict:
    items = [i.model_dump() for i in request.items]
    return {"items": categorizer.predict(_path(request.version), items)}


@app.post("/evaluate")
def evaluate(request: EvaluateRequest) -> dict:
    samples = [s.model_dump() for s in request.samples]
    return categorizer.evaluate(_path(request.version), samples)


@app.post("/forecast")
def forecast_next(request: ForecastRequest) -> dict:
    return forecast.forecast_next(request.values)
