"""Синхронизация данных мобильного приложения с сервером (п. 4.2.1.6 ТЗ).

Мобильное приложение передает очередь операций, накопленных в автономном
режиме, и номер последней известной ему ревизии. Сервер применяет операции и
возвращает все изменения, появившиеся после этой ревизии (в том числе
сделанные с других устройств).

Правила разрешения конфликтов:
  - каждая операция имеет уникальный op_id; повторно присланная операция не
    применяется второй раз (исключено дублирование при обрыве связи);
  - если запись на сервере изменена позже, чем на устройстве, операция
    отклоняется со статусом conflict, а устройство получает серверную версию
    (побеждает последнее по времени изменение);
  - операция, нарушающая бизнес-правило, отклоняется со статусом rejected.
"""
from datetime import UTC

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..audit import log_error
from ..db import get_db
from ..models import SYNC_ENTITIES, Category, SyncLog, SyncOperation, User, utcnow
from ..schemas import SyncChange, SyncRequest, SyncResponse, SyncResult
from ..security import get_current_user
from ..services import entities

router = APIRouter(tags=["Синхронизация"])
# Порядок применения: сначала сущности, на которые ссылаются остальные
ORDER = ["account", "category", "template", "recurring_payment", "transaction", "budget",
         "goal", "goal_contribution", "investment_trade"]


def _apply(db: Session, user: User, change: SyncChange) -> SyncResult:
    def result(status: str, message: str = "") -> SyncResult:
        return SyncResult(op_id=change.op_id, entity=change.entity, id=change.id,
                          status=status, message=message)

    if change.entity not in SYNC_ENTITIES:
        return result("rejected", "Неизвестный тип данных")
    if db.get(SyncOperation, change.op_id) is not None:
        return result("duplicate")
    model = SYNC_ENTITIES[change.entity]
    existing = db.get(model, change.id)
    if existing is not None and existing.user_id != user.id:
        return result("rejected", "Запись принадлежит другому пользователю")
    client_time = change.updated_at
    if client_time.tzinfo is None:
        client_time = client_time.replace(tzinfo=UTC)
    client_time = min(client_time, utcnow())  # время устройства не может опережать серверное
    if existing is not None:
        server_time = existing.updated_at
        if server_time.tzinfo is None:
            server_time = server_time.replace(tzinfo=UTC)
        if server_time > client_time:
            db.add(SyncOperation(op_id=change.op_id, user_id=user.id, result="conflict"))
            return result("conflict", "Запись изменена на другом устройстве позже")
    savepoint = db.begin_nested()
    try:
        if change.deleted:
            if existing is not None:
                entities.delete_entity(db, user, change.entity, change.id, updated_at=client_time)
        else:
            entities.save_entity(db, user, change.entity, change.id, change.data,
                                 updated_at=client_time)
        db.add(SyncOperation(op_id=change.op_id, user_id=user.id, result="applied"))
        savepoint.commit()
        return result("applied")
    except HTTPException as exc:
        savepoint.rollback()
        db.add(SyncOperation(op_id=change.op_id, user_id=user.id, result="rejected"))
        return result("rejected", str(exc.detail))


def _changes_since(db: Session, user: User, since_rev: int) -> dict[str, list[dict]]:
    changes: dict[str, list[dict]] = {}
    for entity in ORDER:
        model = SYNC_ENTITIES[entity]
        query = db.query(model).filter(model.user_id == user.id, model.rev > since_rev)
        items = query.order_by(model.rev).all()
        if entity == "category" and since_rev == 0:
            # При первой синхронизации устройство получает системный справочник
            items = db.query(Category).filter(Category.is_system.is_(True)).all() + items
        if items:
            changes[entity] = [entities.serialize(entity, item) for item in items]
    return changes


@router.post("/sync", response_model=SyncResponse)
def sync(request: SyncRequest, user: User = Depends(get_current_user),
         db: Session = Depends(get_db)):
    """Прием операций автономного режима и выдача изменений с сервера."""
    ordered = sorted(
        request.changes,
        key=lambda c: (ORDER.index(c.entity) if c.entity in ORDER else len(ORDER), c.updated_at),
    )
    results = [_apply(db, user, change) for change in ordered]
    db.flush()
    db.refresh(user)
    conflicts = sum(1 for r in results if r.status == "conflict")
    rejected = [r for r in results if r.status == "rejected"]
    changes = _changes_since(db, user, request.since_rev)
    db.add(SyncLog(
        user_id=user.id, device_id=request.device_id,
        pushed=sum(1 for r in results if r.status == "applied"),
        pulled=sum(len(v) for v in changes.values()), conflicts=conflicts,
        duplicates=sum(1 for r in results if r.status == "duplicate"),
        status="ok" if not rejected else "partial",
        error="; ".join(f"{r.entity}:{r.message}" for r in rejected)[:1000],
    ))
    if rejected:
        log_error(db, "Часть операций синхронизации отклонена", kind="sync",
                  context={"user_id": user.id, "rejected": len(rejected)})
    return SyncResponse(rev=user.sync_rev, server_time=utcnow(), results=results, changes=changes)


@router.get("/sync/status")
def sync_status(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    last = db.query(SyncLog).filter(SyncLog.user_id == user.id).order_by(SyncLog.id.desc()).first()
    return {
        "rev": user.sync_rev,
        "last_sync_at": last.at.isoformat() if last else None,
        "last_status": last.status if last else None,
        "server_time": utcnow().isoformat(),
    }

