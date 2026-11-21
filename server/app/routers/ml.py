"""Контроль качества и управление моделями машинного обучения (роль «Аналитик»)."""
import os
import shutil

from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import func
from sqlalchemy.orm import Session

from ..audit import JOURNAL_ADMIN, audit
from ..config import get_settings
from ..db import get_db
from ..ml import client
from ..ml.categorizer import model_path
from ..models import MlModel, MlTrainingSample, QualitySnapshot, Recommendation, User
from ..security import require_analyst
from ..services import ml_registry

router = APIRouter(prefix="/ml", tags=["Аналитика ИИ"])


class Sample(BaseModel):
    text: str
    kind: str = "expense"
    category: str


class EvaluateIn(BaseModel):
    samples: list[Sample]


def _model_out(model: MlModel) -> dict:
    return {
        "id": model.id, "name": model.name, "version": model.version, "status": model.status,
        "is_active": model.is_active, "samples": model.samples,
        "accuracy": round(model.accuracy, 4), "precision": round(model.precision, 4),
        "recall": round(model.recall, 4), "f1": round(model.f1, 4),
        "unseen_accuracy": round(model.unseen_accuracy, 4),
        "started_at": model.started_at.isoformat(),
        "trained_at": model.trained_at.isoformat() if model.trained_at else None,
        "duration_seconds": model.duration_seconds, "started_by": model.started_by,
        "error": model.error,
    }


@router.get("/quality")
def quality(analyst: User = Depends(require_analyst), db: Session = Depends(get_db)) -> dict:
    """Показатели качества: категоризация, прогноз, оценки рекомендаций и их динамика."""
    model = ml_registry.active_model(db)
    snapshots = db.query(QualitySnapshot).order_by(QualitySnapshot.month.desc()).limit(3).all()
    rated = db.query(func.count(Recommendation.id)).filter(
        Recommendation.feedback.is_not(None)).scalar() or 0
    applied = db.query(func.count(Recommendation.id)).filter(
        Recommendation.status == "applied").scalar() or 0
    return {
        "model": _model_out(model) if model else None,
        "min_accuracy": get_settings().ml_min_accuracy,
        "forecast_mape": snapshots[0].forecast_mape if snapshots else None,
        "positive_feedback_share": ml_registry.feedback_share(db),
        "rated_recommendations": rated,
        "applied_recommendations": applied,
        "training_samples": db.query(func.count(MlTrainingSample.id)).scalar() or 0,
        "history": [
            {"month": s.month, "accuracy": s.accuracy, "precision": s.precision,
             "recall": s.recall, "f1": s.f1, "forecast_mape": s.forecast_mape,
             "positive_feedback_share": s.positive_feedback_share}
            for s in reversed(snapshots)
        ],
    }


@router.get("/models")
def models(analyst: User = Depends(require_analyst), db: Session = Depends(get_db)) -> dict:
    rows = db.query(MlModel).order_by(MlModel.version.desc()).all()
    return {"items": [_model_out(m) for m in rows]}


@router.post("/models/train", status_code=202)
def train(request: Request, analyst: User = Depends(require_analyst),
          db: Session = Depends(get_db)) -> dict:
    """Запуск повторного обучения модели категоризации в фоновом режиме."""
    running = db.query(MlModel).filter(MlModel.status == "training").first()
    if running is not None:
        raise HTTPException(409, "Обучение уже выполняется")
    model = ml_registry.start_training(db, analyst.email)
    audit(db, "ml.train_started", user=analyst, journal=JOURNAL_ADMIN, entity="ml_model",
          entity_id=model.id, details={"version": model.version}, request=request)
    return _model_out(model)


@router.get("/models/{model_id}")
def model_state(model_id: str, analyst: User = Depends(require_analyst),
                db: Session = Depends(get_db)) -> dict:
    model = db.get(MlModel, model_id)
    if model is None:
        raise HTTPException(404, "Модель не найдена")
    return _model_out(model)


@router.post("/models/{model_id}/activate")
def activate(model_id: str, request: Request, analyst: User = Depends(require_analyst),
             db: Session = Depends(get_db)) -> dict:
    """Выбор активной версии модели."""
    model = db.get(MlModel, model_id)
    if model is None or model.status != "ready":
        raise HTTPException(404, "Готовая модель не найдена")
    ml_registry.activate(db, model)
    audit(db, "ml.model_activated", user=analyst, journal=JOURNAL_ADMIN, entity="ml_model",
          entity_id=model.id, details={"version": model.version}, request=request)
    return _model_out(model)


@router.get("/models/{model_id}/export")
def export_model(model_id: str, analyst: User = Depends(require_analyst),
                 db: Session = Depends(get_db)):
    """Экспорт файла модели."""
    model = db.get(MlModel, model_id)
    path = model_path(get_settings().ml_models_dir, model.version) if model else ""
    if model is None or not os.path.exists(path):
        raise HTTPException(404, "Файл модели не найден")
    return FileResponse(path, filename=f"categorizer_v{model.version}.joblib",
                        media_type="application/octet-stream")


@router.post("/models/import", status_code=201)
def import_model(request: Request, file: UploadFile = File(...),
                 analyst: User = Depends(require_analyst),
                 db: Session = Depends(get_db)) -> dict:
    """Импорт файла модели как новой версии (без автоматической активации)."""
    last = db.query(func.max(MlModel.version)).scalar() or 0
    version = last + 1
    settings = get_settings()
    os.makedirs(settings.ml_models_dir, exist_ok=True)
    path = model_path(settings.ml_models_dir, version)
    with open(path, "wb") as target:
        shutil.copyfileobj(file.file, target)
    try:
        client.predict(version, [{"text": "проверка", "kind": "expense"}])
    except Exception as exc:
        os.remove(path)
        raise HTTPException(422, "Файл не является моделью категоризации") from exc
    model = MlModel(name="categorizer", version=version, status="ready", file_path=path,
                    started_by=analyst.email, trained_at=None)
    db.add(model)
    db.flush()
    audit(db, "ml.model_imported", user=analyst, journal=JOURNAL_ADMIN, entity="ml_model",
          entity_id=model.id, details={"version": version, "file": file.filename}, request=request)
    return _model_out(model)


@router.post("/evaluate")
def evaluate(data: EvaluateIn, analyst: User = Depends(require_analyst),
             db: Session = Depends(get_db)) -> dict:
    """Оценка активной модели на контрольной выборке с известными категориями."""
    version = ml_registry.active_version(db)
    if version is None:
        raise HTTPException(404, "Активная модель отсутствует")
    return client.evaluate(version, [s.model_dump() for s in data.samples])


@router.get("/training-data")
def training_data(analyst: User = Depends(require_analyst), db: Session = Depends(get_db)) -> dict:
    """Сведения об обезличенных данных для обучения."""
    by_category = db.query(MlTrainingSample.category_name, func.count(MlTrainingSample.id)) \
        .group_by(MlTrainingSample.category_name).all()
    recent = db.query(MlTrainingSample).order_by(MlTrainingSample.id.desc()).limit(20).all()
    return {
        "total": sum(count for _, count in by_category),
        "by_category": {name: count for name, count in by_category},
        "recent": [{"text": s.text, "kind": s.kind, "category": s.category_name} for s in recent],
    }
