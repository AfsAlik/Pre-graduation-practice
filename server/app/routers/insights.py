"""Интеллектуальные рекомендации, прогнозы и оценка рисков (п. 4.2.1.5 ТЗ)."""
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy.orm import Session

from ..audit import audit
from ..db import get_db
from ..models import Recommendation, RiskAlert, User, utcnow
from ..schemas import Message, Orm
from ..security import get_current_user
from ..services import insights

router = APIRouter(tags=["Рекомендации"])


class RecommendationOut(Orm):
    id: str
    kind: str
    title: str
    text: str
    explanation: str
    estimated_saving: str | float
    action: dict
    status: str
    feedback: str | None
    created_at: object


class FeedbackIn(BaseModel):
    useful: bool


def _own(db: Session, user: User, recommendation_id: str) -> Recommendation:
    item = db.get(Recommendation, recommendation_id)
    if item is None or item.user_id != user.id:
        raise HTTPException(404, "Рекомендация не найдена")
    return item


def _dump(item: Recommendation) -> dict:
    return {
        "id": item.id, "kind": item.kind, "title": item.title, "text": item.text,
        "explanation": item.explanation, "estimated_saving": str(item.estimated_saving),
        "action": item.action or {}, "status": item.status, "feedback": item.feedback,
        "created_at": item.created_at.isoformat(),
        "applied_at": item.applied_at.isoformat() if item.applied_at else None,
    }


@router.get("/recommendations")
def recommendations(refresh: bool = True, user: User = Depends(get_current_user),
                    db: Session = Depends(get_db)) -> dict:
    """Карточки рекомендаций с пояснением и оценкой возможной экономии."""
    if refresh:
        insights.generate_recommendations(db, user)
    items = db.query(Recommendation).filter(
        Recommendation.user_id == user.id, Recommendation.status != "dismissed",
        Recommendation.created_at >= utcnow() - timedelta(days=45),
    ).order_by(Recommendation.created_at.desc()).all()
    return {"currency": user.base_currency, "items": [_dump(i) for i in items]}


@router.post("/recommendations/{recommendation_id}/feedback")
def feedback(recommendation_id: str, data: FeedbackIn, user: User = Depends(get_current_user),
             db: Session = Depends(get_db)) -> dict:
    """Явная обратная связь: оценка «Полезно» / «Не полезно»."""
    item = _own(db, user, recommendation_id)
    item.feedback = "useful" if data.useful else "not_useful"
    return _dump(item)


@router.post("/recommendations/{recommendation_id}/apply")
def apply(recommendation_id: str, request: Request, user: User = Depends(get_current_user),
          db: Session = Depends(get_db)) -> dict:
    """Применение рекомендации (неявная обратная связь): например, установка бюджета."""
    item = _own(db, user, recommendation_id)
    if item.status == "applied":
        raise HTTPException(409, "Рекомендация уже применена")
    result = insights.apply_recommendation(db, user, item)
    audit(db, "recommendation.apply", user=user, entity="recommendation", entity_id=item.id,
          request=request)
    return {**_dump(item), "result": result}


@router.post("/recommendations/{recommendation_id}/dismiss", response_model=Message)
def dismiss(recommendation_id: str, user: User = Depends(get_current_user),
            db: Session = Depends(get_db)):
    _own(db, user, recommendation_id).status = "dismissed"
    return Message(message="Рекомендация скрыта")


@router.get("/forecast")
def forecast(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    """Прогноз расходов и доходов на следующий месяц с интервалом уверенности."""
    return insights.forecast(db, user)


@router.get("/patterns")
def patterns(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    """Финансовые паттерны: регулярные расходы, сезонные колебания, аномалии."""
    return insights.analyze_patterns(db, user)


@router.get("/risks")
def risks(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    """Оценка финансовых рисков с предупреждениями."""
    insights.assess_risks(db, user)
    alerts = db.query(RiskAlert).filter(
        RiskAlert.user_id == user.id, RiskAlert.resolved_at.is_(None)).all()
    return {"items": [
        {"id": a.id, "kind": a.kind, "severity": a.severity, "message": a.message,
         "value": a.value, "created_at": a.created_at.isoformat()} for a in alerts
    ]}
