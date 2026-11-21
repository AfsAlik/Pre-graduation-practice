"""Профиль пользователя, уведомления, согласия, справка и поддержка."""
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from ..audit import JOURNAL_AUTH, audit, log_error
from ..db import get_db
from ..models import BankConsent, Device, Notification, SupportRequest, User, utcnow
from ..schemas import Message, NotificationSettings, ProfileOut, ProfileUpdate
from ..security import get_current_user, revoke_tokens, verify_password
from ..services.currency import list_currencies
from ..services.settings_store import get_value

router = APIRouter()

FAQ = [
    {"question": "Как быстро добавить расход?",
     "answer": "Нажмите кнопку «+» на главном экране, введите сумму и нажмите «Сохранить». "
               "Категория будет определена автоматически по описанию операции."},
    {"question": "Как работает приложение без Интернета?",
     "answer": "Все данные сохраняются на устройстве. Операции, добавленные без подключения, "
               "автоматически отправляются на сервер после восстановления связи."},
    {"question": "Как установить бюджет?",
     "answer": "Откройте раздел «Бюджет», нажмите «Установить лимит», выберите категорию, "
               "период и сумму. При достижении 80 % лимита придет уведомление."},
    {"question": "Как импортировать выписку из банка?",
     "answer": "В разделе «Транзакции» откройте меню и выберите «Импорт выписки». "
               "Поддерживаются файлы CSV, OFX и QIF."},
    {"question": "Почему нельзя изменить сделку в портфеле?",
     "answer": "Количество и цену покупки можно изменить в течение 7 дней. Позже "
               "корректировка оформляется новой сделкой."},
    {"question": "Как защищены мои данные?",
     "answer": "Данные передаются по защищенному протоколу HTTPS, база данных на устройстве "
               "зашифрована, пароли хранятся в виде хеша. Доступна двухфакторная аутентификация."},
]


class DeviceIn(BaseModel):
    device_id: str = Field(min_length=8, max_length=36)
    push_token: str = Field(default="", max_length=512)
    model: str = Field(default="", max_length=100)


class DeleteAccountIn(BaseModel):
    password: str


class ConsentIn(BaseModel):
    provider: str = Field(min_length=2, max_length=50)


class SupportIn(BaseModel):
    subject: str = Field(min_length=3, max_length=200)
    message: str = Field(min_length=5, max_length=5000)


class ClientErrorIn(BaseModel):
    kind: str = Field(default="", max_length=100)
    message: str = Field(max_length=4000)
    level: str = Field(default="error", pattern="^(error|critical)$")
    context: dict = Field(default_factory=dict)


def _profile(user: User) -> ProfileOut:
    data = {c: getattr(user, c) for c in ProfileOut.model_fields if c != "notification_settings"}
    return ProfileOut(**data, notification_settings=NotificationSettings(
        **(user.notification_settings or {})))


@router.get("/profile", response_model=ProfileOut, tags=["Профиль"])
def get_profile(user: User = Depends(get_current_user)):
    return _profile(user)


@router.patch("/profile", response_model=ProfileOut, tags=["Профиль"])
def update_profile(data: ProfileUpdate, request: Request, user: User = Depends(get_current_user),
                   db: Session = Depends(get_db)):
    """Настройка профиля: валюта, риск-профиль, горизонт, тема, уведомления."""
    changes = data.model_dump(exclude_unset=True)
    if "base_currency" in changes and changes["base_currency"] not in list_currencies(db):
        raise HTTPException(422, "Валюта не поддерживается")
    for key, value in changes.items():
        if key == "notification_settings":
            value = NotificationSettings(**value).model_dump()
        setattr(user, key, value)
    audit(db, "profile.update", user=user, request=request, details={"fields": sorted(changes)})
    db.flush()
    return _profile(user)


@router.delete("/profile", response_model=Message, tags=["Профиль"])
def delete_profile(data: DeleteAccountIn, request: Request, user: User = Depends(get_current_user),
                   db: Session = Depends(get_db)):
    """Удаление учетной записи. Персональные данные удаляются в срок не более 30 дней."""
    if not verify_password(data.password, user.password_hash):
        raise HTTPException(400, "Пароль указан неверно")
    user.deleted_at = utcnow()
    revoke_tokens(db, user, "refresh")
    audit(db, "profile.delete_requested", user=user, journal=JOURNAL_AUTH, request=request)
    return Message(message="Учетная запись будет удалена в течение 30 дней")


@router.post("/profile/devices", response_model=Message, tags=["Профиль"])
def register_device(data: DeviceIn, user: User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
    """Регистрация устройства и токена доставки push-уведомлений."""
    device = db.get(Device, data.device_id)
    if device is None:
        db.add(Device(id=data.device_id, user_id=user.id, push_token=data.push_token,
                      model=data.model))
    else:
        device.user_id, device.push_token = user.id, data.push_token
        device.model, device.last_seen_at = data.model, utcnow()
    return Message(message="Устройство зарегистрировано")


# ------------------------------------------------------------------ уведомления
@router.get("/notifications", tags=["Уведомления"])
def list_notifications(unread_only: bool = False, limit: int = 50,
                       user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    query = db.query(Notification).filter(Notification.user_id == user.id)
    if unread_only:
        query = query.filter(Notification.read_at.is_(None))
    items = query.order_by(Notification.created_at.desc()).limit(min(limit, 200)).all()
    return {"items": [
        {"id": n.id, "kind": n.kind, "title": n.title, "body": n.body, "payload": n.payload,
         "important": n.important, "created_at": n.created_at.isoformat(),
         "push_sent_at": n.push_sent_at.isoformat() if n.push_sent_at else None,
         "email_sent_at": n.email_sent_at.isoformat() if n.email_sent_at else None,
         "read": n.read_at is not None} for n in items
    ]}


@router.post("/notifications/{notification_id}/read", response_model=Message, tags=["Уведомления"])
def read_notification(notification_id: str, user: User = Depends(get_current_user),
                      db: Session = Depends(get_db)):
    item = db.get(Notification, notification_id)
    if item is None or item.user_id != user.id:
        raise HTTPException(404, "Уведомление не найдено")
    item.read_at = item.read_at or utcnow()
    return Message(message="Уведомление прочитано")


# ------------------------------------------------------------------ согласия
@router.get("/bank-consents", tags=["Интеграции"])
def list_consents(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    items = db.query(BankConsent).filter(
        BankConsent.user_id == user.id, BankConsent.revoked_at.is_(None)).all()
    return {"items": [{"id": c.id, "provider": c.provider,
                       "granted_at": c.granted_at.isoformat()} for c in items]}


@router.post("/bank-consents", response_model=Message, status_code=201, tags=["Интеграции"])
def grant_consent(data: ConsentIn, request: Request, user: User = Depends(get_current_user),
                  db: Session = Depends(get_db)):
    """Явное согласие пользователя на подключение внешнего источника банковских данных."""
    db.add(BankConsent(user_id=user.id, provider=data.provider))
    audit(db, "consent.granted", user=user, request=request, details={"provider": data.provider})
    return Message(message="Согласие получено")


@router.delete("/bank-consents/{provider}", response_model=Message, tags=["Интеграции"])
def revoke_consent(provider: str, request: Request, user: User = Depends(get_current_user),
                   db: Session = Depends(get_db)):
    """Отзыв согласия: подключение источника прекращается."""
    db.query(BankConsent).filter(
        BankConsent.user_id == user.id, BankConsent.provider == provider,
        BankConsent.revoked_at.is_(None)).update({"revoked_at": utcnow()})
    audit(db, "consent.revoked", user=user, request=request, details={"provider": provider})
    return Message(message="Согласие отозвано")


@router.post("/bank-connections", tags=["Интеграции"])
def connect_bank(data: ConsentIn, user: User = Depends(get_current_user),
                 db: Session = Depends(get_db)) -> dict:
    """Подключение внешнего источника банковских данных.

    Выполняется только при наличии действующего согласия пользователя
    (п. 4.2.1.7 д ТЗ). В первой версии системы данные банка загружаются
    импортом выписки; автоматическая синхронизация предусмотрена развитием.
    """
    consent = db.query(BankConsent).filter(
        BankConsent.user_id == user.id, BankConsent.provider == data.provider,
        BankConsent.revoked_at.is_(None)).first()
    if consent is None:
        raise HTTPException(403, "Подключение возможно только после явного согласия пользователя")
    return {"provider": data.provider, "status": "import_only",
            "message": "Согласие получено. Загрузите выписку банка в разделе «Транзакции»."}


# ------------------------------------------------------------------ справка и поддержка
@router.get("/support/faq", tags=["Поддержка"])
def faq() -> dict:
    return {"items": FAQ}


@router.post("/support/requests", response_model=Message, status_code=201, tags=["Поддержка"])
def support_request(data: SupportIn, user: User = Depends(get_current_user),
                    db: Session = Depends(get_db)):
    recent = db.query(SupportRequest).filter(
        SupportRequest.user_id == user.id,
        SupportRequest.created_at >= utcnow() - timedelta(minutes=1)).count()
    if recent >= 3:
        raise HTTPException(429, "Слишком много обращений. Повторите позже")
    db.add(SupportRequest(user_id=user.id, subject=data.subject, message=data.message))
    return Message(message="Обращение отправлено в службу поддержки")


@router.post("/client-errors", response_model=Message, status_code=201, tags=["Поддержка"])
def client_error(data: ClientErrorIn, user: User = Depends(get_current_user),
                 db: Session = Depends(get_db)):
    """Прием записей журнала ошибок мобильного приложения."""
    log_error(db, data.message, kind=data.kind, source="client", level=data.level,
              context={**data.context, "user_id": user.id})
    return Message(message="Ошибка зарегистрирована")


@router.get("/system/status", tags=["Справочники"])
def system_status(db: Session = Depends(get_db)) -> dict:
    """Режим работы системы: штатный или техническое обслуживание."""
    maintenance = get_value(db, "maintenance")
    return {"maintenance": bool(maintenance.get("enabled")),
            "message": maintenance.get("message", ""), "server_time": utcnow().isoformat()}

