"""Версии модели категоризации, ее применение и повторное обучение."""
import logging
import time
from datetime import date

from sqlalchemy import func
from sqlalchemy.orm import Session

from ..config import get_settings
from ..ml import client
from ..ml.categorizer import normalize
from ..ml.dataset import build_training_set
from ..models import (
    Category,
    MlModel,
    MlTrainingSample,
    QualitySnapshot,
    Recommendation,
    Transaction,
    utcnow,
)
from .categories import category_map, find_by_name, root_of
from .tasks import enqueue, task

log = logging.getLogger(__name__)
MIN_CONFIDENCE = 0.30
_active_cache: dict = {"at": 0.0, "version": None}


def active_model(db: Session) -> MlModel | None:
    return (
        db.query(MlModel)
        .filter(MlModel.name == "categorizer", MlModel.is_active.is_(True))
        .order_by(MlModel.version.desc())
        .first()
    )


def active_version(db: Session) -> int | None:
    """Номер активной версии модели (кешируется на 30 секунд)."""
    now = time.monotonic()
    if _active_cache["version"] is None or now - _active_cache["at"] > 30:
        model = active_model(db)
        _active_cache.update(at=now, version=model.version if model else None)
    return _active_cache["version"]


def reset_cache() -> None:
    _active_cache.update(at=0.0, version=None)


def collect_samples(db: Session) -> list[dict]:
    """Обучающая выборка: начальный набор и обезличенные операции пользователей."""
    samples = build_training_set()
    for row in db.query(MlTrainingSample).all():
        samples.append({"text": row.text, "kind": row.kind, "category": row.category_name})
    return samples


def remember_sample(db: Session, user_id: str, transaction: Transaction) -> None:
    """Сохраняет обезличенный пример для обучения (п. 4.2.1.5 е ТЗ).

    Сохраняются только нормализованный текст операции (без цифр), ее вид и
    название системной категории верхнего уровня. Идентификатор пользователя,
    сумма и дата не сохраняются.
    """
    text = normalize(f"{transaction.merchant} {transaction.comment}")
    if len(text) < 3 or not transaction.category_id or transaction.kind == "transfer":
        return
    root = root_of(category_map(db, user_id), transaction.category_id)
    if root is None or not root.is_system:
        return
    exists = (
        db.query(MlTrainingSample.id)
        .filter(MlTrainingSample.text == text[:300], MlTrainingSample.category_name == root.name)
        .first()
    )
    if exists is None:
        db.add(MlTrainingSample(text=text[:300], kind=transaction.kind, category_name=root.name))


def suggest_category(db: Session, user_id: str, text: str, kind: str) -> dict | None:
    """Определяет категорию операции.

    Сначала используется история пользователя: категория его последней операции
    с тем же текстом. Если такой операции нет, применяется модель классификации.
    """
    normalized = normalize(text)
    if len(normalized) < 2 or kind == "transfer":
        return None
    history = (
        db.query(Transaction)
        .filter(
            Transaction.user_id == user_id, Transaction.deleted_at.is_(None),
            Transaction.kind == kind, Transaction.category_id.is_not(None),
            Transaction.auto_categorized.is_(False),
        )
        .order_by(Transaction.occurred_at.desc())
        .limit(300)
        .all()
    )
    for item in history:
        if normalize(f"{item.merchant} {item.comment}") == normalized:
            category = db.get(Category, item.category_id)
            if category is not None and category.deleted_at is None:
                return {"category_id": category.id, "category_name": category.name,
                        "confidence": 1.0, "source": "history"}
    version = active_version(db)
    if version is None:
        return None
    try:
        prediction = client.predict(version, [{"text": text, "kind": kind}])[0]
    except Exception:
        log.exception("Сервис машинного обучения недоступен")
        return None
    if prediction["confidence"] < MIN_CONFIDENCE:
        return None
    category = find_by_name(db, user_id, prediction["category"], kind)
    if category is None:
        return None
    return {"category_id": category.id, "category_name": category.name,
            "confidence": round(prediction["confidence"], 3), "source": "model"}


def start_training(db: Session, started_by: str) -> MlModel:
    """Создает новую версию модели и ставит ее обучение в очередь фоновых задач."""
    last = db.query(func.max(MlModel.version)).filter(MlModel.name == "categorizer").scalar() or 0
    model = MlModel(name="categorizer", version=last + 1, status="training", started_by=started_by)
    db.add(model)
    db.flush()
    enqueue(db, "train_categorizer", model_id=model.id)
    return model


@task("train_categorizer")
def train_categorizer(db: Session, model_id: str) -> None:
    model = db.get(MlModel, model_id)
    if model is None:
        return
    started = time.monotonic()
    try:
        metrics = client.train(collect_samples(db), model.version)
    except Exception as exc:
        model.status, model.error = "failed", str(exc)
        log.exception("Ошибка обучения модели версии %s", model.version)
        return
    model.status = "ready"
    model.trained_at = utcnow()
    model.duration_seconds = round(time.monotonic() - started, 3)
    model.samples = metrics["samples"]
    model.accuracy, model.precision = metrics["accuracy"], metrics["precision"]
    model.recall, model.f1 = metrics["recall"], metrics["f1"]
    model.unseen_accuracy = metrics.get("unseen_accuracy", 0.0)
    model.file_path = metrics.get("file_path", "")
    if model.accuracy >= get_settings().ml_min_accuracy:
        activate(db, model)
    save_snapshot(db)


def activate(db: Session, model: MlModel) -> None:
    """Делает версию модели активной (используемой для категоризации)."""
    db.query(MlModel).filter(MlModel.name == model.name).update({"is_active": False})
    model.is_active = True
    db.flush()
    reset_cache()


def ensure_active_model(db: Session) -> MlModel:
    """Обучает начальную версию модели, если активной версии нет (развертывание)."""
    model = active_model(db)
    if model is not None:
        return model
    last = db.query(func.max(MlModel.version)).filter(MlModel.name == "categorizer").scalar() or 0
    model = MlModel(name="categorizer", version=last + 1, status="training", started_by="system")
    db.add(model)
    db.flush()
    train_categorizer(db, model.id)
    if not model.is_active and model.status == "ready":
        activate(db, model)
    return model


def feedback_share(db: Session) -> float:
    """Доля положительных оценок рекомендаций среди всех оценок."""
    total = db.query(func.count(Recommendation.id)).filter(
        Recommendation.feedback.is_not(None)).scalar() or 0
    if total == 0:
        return 0.0
    useful = db.query(func.count(Recommendation.id)).filter(
        Recommendation.feedback == "useful").scalar() or 0
    return round(useful / total, 4)


def save_snapshot(db: Session, forecast_mape: float | None = None) -> QualitySnapshot:
    """Сохраняет срез показателей качества за текущий месяц."""
    month = date.today().strftime("%Y-%m")
    snapshot = db.query(QualitySnapshot).filter(QualitySnapshot.month == month).first()
    if snapshot is None:
        snapshot = QualitySnapshot(month=month)
        db.add(snapshot)
    model = active_model(db)
    if model is not None:
        snapshot.accuracy, snapshot.precision = model.accuracy, model.precision
        snapshot.recall, snapshot.f1 = model.recall, model.f1
    if forecast_mape is not None:
        snapshot.forecast_mape = forecast_mape
    snapshot.positive_feedback_share = feedback_share(db)
    db.flush()
    return snapshot
