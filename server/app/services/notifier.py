"""Формирование и доставка уведомлений (подсистема уведомлений, п. 4.2.1.6 ТЗ).

Уведомление сохраняется в БД вместе с временем события, после чего фоновая
задача передает его в сервис доставки push-уведомлений и, для важных
уведомлений, на электронную почту. Каналы настраиваются пользователем.
Поставщики подключаются параметрами FH_PUSH_PROVIDER и FH_EMAIL_PROVIDER;
значение log означает запись сообщения в журнал вместо реальной отправки.
"""
import logging
import smtplib
from email.message import EmailMessage

from sqlalchemy.orm import Session

from ..config import get_settings
from ..models import Device, Notification, User, utcnow
from ..schemas import NotificationSettings
from .settings_store import integration
from .tasks import enqueue, task

log = logging.getLogger(__name__)

# Вид уведомления -> поле настроек пользователя, разрешающее его отправку
KIND_SETTING = {
    "budget_warning": "budget",
    "budget_exceeded": "budget",
    "recurring_payment": "recurring",
    "goal_achieved": "goals",
    "recommendation": "recommendations",
    "risk": "risks",
}
# Последние отправленные сообщения в режиме log (используются тестами)
outbox: list[dict] = []


def user_settings(user: User) -> NotificationSettings:
    return NotificationSettings(**(user.notification_settings or {}))


def notify(
    db: Session,
    user: User,
    kind: str,
    title: str,
    body: str = "",
    *,
    important: bool = False,
    payload: dict | None = None,
) -> Notification | None:
    """Создает уведомление и ставит его доставку в очередь фоновых задач."""
    settings = user_settings(user)
    flag = KIND_SETTING.get(kind)
    if flag and not getattr(settings, flag):
        return None
    notification = Notification(
        user_id=user.id, kind=kind, title=title, body=body,
        important=important, payload=payload or {}, event_at=utcnow(),
    )
    db.add(notification)
    db.flush()
    enqueue(db, "deliver_notification", notification_id=notification.id)
    return notification


def send_push(tokens: list[str], title: str, body: str, data: dict, provider: str | None = None) -> None:
    settings = get_settings()
    if (provider or settings.push_provider) == "fcm" and settings.fcm_credentials_file:
        _send_fcm(tokens, title, body, data)
    else:
        outbox.append({"channel": "push", "tokens": tokens, "title": title, "body": body})
        del outbox[:-200]
        log.info("PUSH -> %d устройств: %s | %s", len(tokens), title, body)


def _send_fcm(tokens: list[str], title: str, body: str, data: dict) -> None:
    """Отправка через Firebase Cloud Messaging (требуется пакет firebase-admin)."""
    import firebase_admin
    from firebase_admin import credentials, messaging

    if not firebase_admin._apps:
        firebase_admin.initialize_app(
            credentials.Certificate(get_settings().fcm_credentials_file)
        )
    for token in tokens:
        message = messaging.Message(
            token=token,
            notification=messaging.Notification(title=title, body=body),
            data={k: str(v) for k, v in data.items()},
        )
        messaging.send(message)


def send_email(to: str, subject: str, body: str, provider: str | None = None) -> None:
    settings = get_settings()
    if (provider or settings.email_provider) == "smtp" and settings.smtp_host:
        message = EmailMessage()
        message["From"], message["To"], message["Subject"] = settings.smtp_from, to, subject
        message.set_content(body)
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=15) as smtp:
            smtp.starttls()
            if settings.smtp_user:
                smtp.login(settings.smtp_user, settings.smtp_password)
            smtp.send_message(message)
    else:
        outbox.append({"channel": "email", "to": to, "title": subject, "body": body})
        del outbox[:-200]
        log.info("EMAIL -> %s: %s | %s", to, subject, body)


@task("deliver_notification")
def deliver_notification(db: Session, notification_id: str) -> None:
    notification = db.get(Notification, notification_id)
    if notification is None:
        return
    user = db.get(User, notification.user_id)
    if user is None:
        return
    settings = user_settings(user)
    if settings.push_enabled and notification.push_sent_at is None:
        tokens = [
            d.push_token
            for d in db.query(Device).filter(Device.user_id == user.id)
            if d.push_token
        ]
        try:
            send_push(tokens, notification.title, notification.body,
                      {"kind": notification.kind, "id": notification.id, **notification.payload},
                      integration(db, "push_provider"))
            notification.push_sent_at = utcnow()
        except Exception:
            log.exception("Не удалось передать push-уведомление %s", notification.id)
    if settings.email_enabled and notification.important and notification.email_sent_at is None:
        try:
            send_email(user.email, notification.title, notification.body,
                       integration(db, "email_provider"))
            notification.email_sent_at = utcnow()
        except Exception:
            log.exception("Не удалось отправить письмо для уведомления %s", notification.id)


@task("send_email")
def send_email_task(db: Session, to: str, subject: str, body: str) -> None:
    send_email(to, subject, body, integration(db, "email_provider"))
