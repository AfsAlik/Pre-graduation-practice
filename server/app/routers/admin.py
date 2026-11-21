"""Администрирование: учетные записи, журналы, мониторинг, настройки (п. 4.2.1.8 ТЗ)."""
import csv
import io
import secrets
from datetime import UTC, date, datetime, timedelta

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy import or_
from sqlalchemy.orm import Session

from ..audit import JOURNAL_ADMIN, audit
from ..db import contains, get_db
from ..models import AuditLog, ErrorLog, SupportRequest, SyncLog, User, utcnow
from ..schemas import Message, NotificationSettings
from ..security import ROLE_TITLES, ROLES, hash_password, require_admin, revoke_tokens
from ..services import monitoring, settings_store
from ..services.categories import SYSTEM_USER_ID
from ..services.periods import day_end, day_start
from ..services.tasks import enqueue

router = APIRouter(prefix="/admin", tags=["Администрирование"])
JOB_NAMES = ("health_check", "recurring_reminders", "daily")
CSV_HEADER = ["at", "journal", "user_email", "action", "entity", "entity_id", "ip", "details"]


class UserCreateIn(BaseModel):
    email: EmailStr
    display_name: str = Field(default="", max_length=100)
    role: str = Field(default="user")
    password: str | None = Field(default=None, min_length=8, max_length=128)


class UserUpdateIn(BaseModel):
    role: str | None = None
    display_name: str | None = Field(default=None, max_length=100)
    expected_updated_at: datetime | None = None


class SettingIn(BaseModel):
    value: dict


def _user_out(user: User) -> dict:
    return {
        "id": user.id, "email": user.email, "display_name": user.display_name,
        "role": user.role, "role_title": ROLE_TITLES.get(user.role, user.role),
        "is_blocked": user.is_blocked, "email_confirmed": user.email_confirmed,
        "totp_enabled": user.totp_enabled,
        "created_at": user.created_at.isoformat(),
        "last_login_at": user.last_login_at.isoformat() if user.last_login_at else None,
    }


def _target(db: Session, user_id: str) -> User:
    user = db.get(User, user_id)
    if user is None or user.id == SYSTEM_USER_ID or user.deleted_at is not None:
        raise HTTPException(404, "Учетная запись не найдена")
    return user


def _temporary_password() -> str:
    return secrets.token_urlsafe(9) + "7a"


# ------------------------------------------------------------------ учетные записи
@router.get("/users")
def list_users(search: str | None = None, role: str | None = None,
               limit: int = Query(default=50, le=200), offset: int = 0,
               admin: User = Depends(require_admin), db: Session = Depends(get_db)) -> dict:
    query = db.query(User).filter(User.id != SYSTEM_USER_ID, User.deleted_at.is_(None))
    if search:
        query = query.filter(or_(contains(User.email, search), contains(User.display_name, search)))
    if role:
        query = query.filter(User.role == role)
    total = query.count()
    users = query.order_by(User.created_at).offset(offset).limit(limit).all()
    return {"total": total, "items": [_user_out(u) for u in users]}


@router.post("/users", status_code=201)
def create_user(data: UserCreateIn, request: Request, admin: User = Depends(require_admin),
                db: Session = Depends(get_db)) -> dict:
    """Создание учетной записи с назначением роли."""
    if data.role not in ROLES:
        raise HTTPException(422, "Неизвестная роль")
    email = data.email.lower()
    if db.query(User).filter(User.email == email).first() is not None:
        raise HTTPException(409, "Учетная запись с таким адресом уже существует")
    password = data.password or _temporary_password()
    user = User(email=email, display_name=data.display_name, role=data.role,
                password_hash=hash_password(password), email_confirmed=True,
                consent_pd_at=utcnow(), notification_settings=NotificationSettings().model_dump())
    db.add(user)
    db.flush()
    audit(db, "admin.user_create", user=admin, journal=JOURNAL_ADMIN, entity="user",
          entity_id=user.id, details={"email": email, "role": data.role}, request=request)
    result = _user_out(user)
    if data.password is None:
        result["temporary_password"] = password
    return result


@router.patch("/users/{user_id}")
def update_user(user_id: str, data: UserUpdateIn, request: Request,
                admin: User = Depends(require_admin), db: Session = Depends(get_db)) -> dict:
    """Изменение роли и имени учетной записи.

    Строка учетной записи блокируется на время изменения, поэтому одновременные
    действия двух администраторов выполняются последовательно и не теряются.
    """
    user = db.query(User).filter(User.id == user_id).with_for_update().first()
    if user is None or user.id == SYSTEM_USER_ID or user.deleted_at is not None:
        raise HTTPException(404, "Учетная запись не найдена")
    changes = data.model_dump(exclude_unset=True, exclude={"expected_updated_at"})
    if "role" in changes:
        if changes["role"] not in ROLES:
            raise HTTPException(422, "Неизвестная роль")
        if user.id == admin.id and changes["role"] != "admin":
            raise HTTPException(409, "Нельзя снять роль администратора с собственной учетной записи")
    for key, value in changes.items():
        setattr(user, key, value)
    audit(db, "admin.user_update", user=admin, journal=JOURNAL_ADMIN, entity="user",
          entity_id=user.id, details=changes, request=request)
    return _user_out(user)


@router.post("/users/{user_id}/block")
def block_user(user_id: str, request: Request, admin: User = Depends(require_admin),
               db: Session = Depends(get_db)) -> dict:
    user = _target(db, user_id)
    if user.id == admin.id:
        raise HTTPException(409, "Нельзя заблокировать собственную учетную запись")
    user.is_blocked = True
    revoke_tokens(db, user, "refresh")
    audit(db, "admin.user_block", user=admin, journal=JOURNAL_ADMIN, entity="user",
          entity_id=user.id, request=request)
    return _user_out(user)


@router.post("/users/{user_id}/unblock")
def unblock_user(user_id: str, request: Request, admin: User = Depends(require_admin),
                 db: Session = Depends(get_db)) -> dict:
    user = _target(db, user_id)
    user.is_blocked, user.failed_logins, user.locked_until = False, 0, None
    audit(db, "admin.user_unblock", user=admin, journal=JOURNAL_ADMIN, entity="user",
          entity_id=user.id, request=request)
    return _user_out(user)


@router.post("/users/{user_id}/reset-password")
def reset_user_password(user_id: str, request: Request, admin: User = Depends(require_admin),
                        db: Session = Depends(get_db)) -> dict:
    """Сброс пароля: пользователю назначается временный пароль и отправляется письмо."""
    user = _target(db, user_id)
    password = _temporary_password()
    user.password_hash = hash_password(password)
    user.failed_logins, user.locked_until = 0, None
    revoke_tokens(db, user, "refresh")
    enqueue(db, "send_email", to=user.email, subject="Пароль сброшен администратором",
            body=f"Ваш временный пароль: {password}. Смените его после входа в приложение.")
    audit(db, "admin.user_reset_password", user=admin, journal=JOURNAL_ADMIN, entity="user",
          entity_id=user.id, request=request)
    return {**_user_out(user), "temporary_password": password}


# ------------------------------------------------------------------ журналы
def _logs_query(db: Session, date_from, date_to, user, action, journal, origin):
    query = db.query(AuditLog)
    if date_from:
        query = query.filter(AuditLog.at >= day_start(date_from))
    if date_to:
        query = query.filter(AuditLog.at <= day_end(date_to))
    if user:
        query = query.filter(or_(contains(AuditLog.user_email, user), AuditLog.user_id == user))
    if action:
        query = query.filter(AuditLog.action.startswith(action))
    if journal:
        query = query.filter(AuditLog.journal == journal)
    if origin:
        query = query.filter(AuditLog.origin == origin)
    return query


def _log_out(row: AuditLog) -> dict:
    return {"id": row.id, "at": row.at.isoformat(), "journal": row.journal,
            "user_email": row.user_email, "action": row.action, "entity": row.entity,
            "entity_id": row.entity_id, "ip": row.ip, "details": row.details, "origin": row.origin}


@router.get("/logs")
def list_logs(date_from: date | None = None, date_to: date | None = None,
              user: str | None = None, action: str | None = None, journal: str | None = None,
              origin: str | None = None, limit: int = Query(default=100, le=1000), offset: int = 0,
              admin: User = Depends(require_admin), db: Session = Depends(get_db)) -> dict:
    """Журнал аудита с фильтрацией по дате, пользователю и типу действия."""
    query = _logs_query(db, date_from, date_to, user, action, journal, origin)
    total = query.count()
    rows = query.order_by(AuditLog.at.desc(), AuditLog.id.desc()).offset(offset).limit(limit).all()
    return {"total": total, "items": [_log_out(r) for r in rows]}


@router.get("/logs/export")
def export_logs(request: Request, date_from: date | None = None, date_to: date | None = None,
                user: str | None = None, action: str | None = None, journal: str | None = None,
                admin: User = Depends(require_admin), db: Session = Depends(get_db)):
    """Экспорт журнала аудита в файл CSV."""
    rows = _logs_query(db, date_from, date_to, user, action, journal, None) \
        .order_by(AuditLog.at).limit(100000).all()
    buffer = io.StringIO()
    writer = csv.writer(buffer, delimiter=";")
    writer.writerow(CSV_HEADER)
    for row in rows:
        writer.writerow([row.at.isoformat(), row.journal, row.user_email, row.action, row.entity,
                         row.entity_id, row.ip, str(row.details or {})])
    audit(db, "admin.logs_export", user=admin, journal=JOURNAL_ADMIN, request=request,
          details={"rows": len(rows)})
    return Response(buffer.getvalue().encode("utf-8-sig"), media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": "attachment; filename=audit_log.csv"})


@router.post("/logs/import")
async def import_logs(request: Request, file: UploadFile = File(...),
                      admin: User = Depends(require_admin), db: Session = Depends(get_db)) -> dict:
    """Импорт внешнего журнала из файла CSV для анализа (записи помечаются origin=import)."""
    content = (await file.read()).decode("utf-8-sig", errors="replace")
    reader = csv.DictReader(io.StringIO(content), delimiter=";")
    if not reader.fieldnames or "at" not in reader.fieldnames or "action" not in reader.fieldnames:
        raise HTTPException(422, "Файл должен содержать столбцы at и action")
    imported = 0
    for row in reader:
        try:
            at = datetime.fromisoformat(row["at"])
        except (ValueError, TypeError):
            continue
        if at.tzinfo is None:
            at = at.replace(tzinfo=UTC)
        db.add(AuditLog(at=at, journal=(row.get("journal") or "data")[:10],
                        user_email=(row.get("user_email") or "")[:255],
                        action=(row.get("action") or "")[:50], entity=(row.get("entity") or "")[:50],
                        entity_id=(row.get("entity_id") or "")[:64], ip=(row.get("ip") or "")[:45],
                        details={"raw": row.get("details") or ""}, origin="import"))
        imported += 1
    audit(db, "admin.logs_import", user=admin, journal=JOURNAL_ADMIN, request=request,
          details={"file": file.filename, "rows": imported})
    return {"imported": imported}


@router.get("/errors")
def list_errors(source: str | None = None, hours: int = 24, limit: int = Query(default=100, le=500),
                admin: User = Depends(require_admin), db: Session = Depends(get_db)) -> dict:
    query = db.query(ErrorLog).filter(ErrorLog.at >= utcnow() - timedelta(hours=hours))
    if source:
        query = query.filter(ErrorLog.source == source)
    rows = query.order_by(ErrorLog.at.desc()).limit(limit).all()
    return {"items": [{"id": r.id, "at": r.at.isoformat(), "source": r.source, "level": r.level,
                       "kind": r.kind, "message": r.message, "context": r.context} for r in rows]}


@router.get("/sync-log")
def sync_log(limit: int = Query(default=100, le=500), admin: User = Depends(require_admin),
             db: Session = Depends(get_db)) -> dict:
    rows = db.query(SyncLog).order_by(SyncLog.id.desc()).limit(limit).all()
    return {"items": [{"at": r.at.isoformat(), "user_id": r.user_id, "device_id": r.device_id,
                       "pushed": r.pushed, "pulled": r.pulled, "conflicts": r.conflicts,
                       "duplicates": r.duplicates, "status": r.status, "error": r.error}
                      for r in rows]}


# ------------------------------------------------------------------ мониторинг и настройки
@router.get("/monitoring")
def monitoring_summary(hours: int = Query(default=24, ge=1, le=24 * 31),
                       admin: User = Depends(require_admin), db: Session = Depends(get_db)) -> dict:
    """Доступность серверной части, среднее время ответа, ошибки, график доступности."""
    return monitoring.summary(db, hours)


@router.post("/jobs/{name}/run", response_model=Message, status_code=202)
def run_job(name: str, request: Request, admin: User = Depends(require_admin),
            db: Session = Depends(get_db)):
    """Запуск периодической задачи вне расписания (например, после восстановления сервера)."""
    if name not in JOB_NAMES:
        raise HTTPException(404, f"Неизвестная задача. Доступные задачи: {', '.join(JOB_NAMES)}")
    enqueue(db, "run_job", job=name)
    audit(db, "admin.job_run", user=admin, journal=JOURNAL_ADMIN, entity="job", entity_id=name,
          request=request)
    return Message(message=f"Задача {name} поставлена в очередь")


@router.get("/settings")
def get_settings_all(admin: User = Depends(require_admin), db: Session = Depends(get_db)) -> dict:
    return settings_store.get_all(db)


@router.put("/settings/{key}", response_model=Message)
def put_setting(key: str, data: SettingIn, request: Request, admin: User = Depends(require_admin),
                db: Session = Depends(get_db)):
    """Изменение системной настройки: интеграции, пороги уведомлений, расписание копирования."""
    if key not in settings_store.defaults():
        raise HTTPException(404, "Неизвестная настройка")
    settings_store.set_value(db, key, data.value, admin.email)
    audit(db, "admin.setting_update", user=admin, journal=JOURNAL_ADMIN, entity="setting",
          entity_id=key, details=data.value, request=request)
    return Message(message="Настройка сохранена")


@router.get("/support-requests")
def support_requests(admin: User = Depends(require_admin), db: Session = Depends(get_db)) -> dict:
    rows = db.query(SupportRequest).order_by(SupportRequest.created_at.desc()).limit(200).all()
    return {"items": [{"id": r.id, "user_id": r.user_id, "subject": r.subject,
                       "message": r.message, "status": r.status,
                       "created_at": r.created_at.isoformat()} for r in rows]}
