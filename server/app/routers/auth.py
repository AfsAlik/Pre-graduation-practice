"""Регистрация и аутентификация пользователей (п. 4.2.1.8 а, б ТЗ)."""
from datetime import UTC, timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy.orm import Session

from ..audit import JOURNAL_AUTH, audit
from ..config import get_settings
from ..db import get_db
from ..models import User, utcnow
from ..schemas import Message, NotificationSettings
from ..security import (
    consume_token,
    create_access_token,
    get_current_user,
    hash_password,
    issue_token,
    new_totp_secret,
    revoke_tokens,
    totp_uri,
    validate_password_strength,
    verify_password,
    verify_totp,
)
from ..services import settings_store
from ..services.notifier import notify
from ..services.tasks import enqueue

router = APIRouter(prefix="/auth", tags=["Аутентификация"])


class RegisterIn(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    display_name: str = Field(default="", max_length=100)
    consent_personal_data: bool = False


class LoginIn(BaseModel):
    email: EmailStr
    password: str
    totp_code: str | None = None


class TokenOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    role: str
    user_id: str


class RefreshIn(BaseModel):
    refresh_token: str


class ForgotIn(BaseModel):
    email: EmailStr


class ResetIn(BaseModel):
    token: str
    new_password: str = Field(min_length=8, max_length=128)


class ChangePasswordIn(BaseModel):
    current_password: str
    new_password: str = Field(min_length=8, max_length=128)


class TotpCodeIn(BaseModel):
    code: str = Field(min_length=6, max_length=8)


def _tokens(db: Session, user: User) -> TokenOut:
    settings = get_settings()
    refresh = issue_token(db, user, "refresh", timedelta(days=settings.refresh_token_days))
    return TokenOut(
        access_token=create_access_token(user), refresh_token=refresh,
        expires_in=settings.access_token_minutes * 60, role=user.role, user_id=user.id,
    )


def send_confirmation(db: Session, user: User) -> str:
    settings = get_settings()
    token = issue_token(db, user, "email_confirm", timedelta(hours=settings.email_confirm_hours))
    link = f"{settings.public_base_url}/api/v1/auth/confirm-email?token={token}"
    enqueue(db, "send_email", to=user.email, subject="Подтверждение адреса электронной почты",
            body=f"Для завершения регистрации в приложении «Финансовый помощник» перейдите "
                 f"по ссылке: {link}")
    return token


@router.post("/register", response_model=Message, status_code=status.HTTP_201_CREATED)
def register(data: RegisterIn, request: Request, db: Session = Depends(get_db)):
    """Регистрация учетной записи. Согласие на обработку персональных данных обязательно."""
    if not data.consent_personal_data:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "Для регистрации необходимо согласие на обработку персональных данных",
        )
    validate_password_strength(data.password)
    email = data.email.lower()
    if db.query(User).filter(User.email == email).first() is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "Учетная запись с таким адресом уже существует")
    settings = get_settings()
    user = User(
        email=email, display_name=data.display_name, password_hash=hash_password(data.password),
        consent_pd_at=utcnow(), base_currency=settings.default_base_currency,
        email_confirmed=not settings.require_email_confirmation,
        notification_settings=NotificationSettings(
            budget_threshold_percent=int(settings_store.get_value(db, "notifications").get(
                "budget_warning_percent", settings.budget_warning_percent))).model_dump(),
    )
    db.add(user)
    db.flush()
    if settings.require_email_confirmation:
        send_confirmation(db, user)
    audit(db, "auth.register", user=user, journal=JOURNAL_AUTH, request=request)
    return Message(message="Учетная запись создана. Подтвердите адрес электронной почты по ссылке из письма")


@router.get("/confirm-email", response_class=HTMLResponse)
def confirm_email(token: str, db: Session = Depends(get_db)):
    user = consume_token(db, token, "email_confirm")
    if user is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Ссылка недействительна или устарела")
    user.email_confirmed = True
    audit(db, "auth.email_confirmed", user=user, journal=JOURNAL_AUTH)
    return "<h3>Адрес электронной почты подтвержден. Вернитесь в приложение и выполните вход.</h3>"


@router.post("/login", response_model=TokenOut)
def login(data: LoginIn, request: Request, db: Session = Depends(get_db)):
    """Вход в систему. После пяти неудачных попыток вход блокируется на 15 минут."""
    settings = get_settings()
    email = data.email.lower()
    user = db.query(User).filter(User.email == email, User.deleted_at.is_(None)).first()
    invalid = HTTPException(status.HTTP_401_UNAUTHORIZED, "Неверный адрес электронной почты или пароль")
    if user is None:
        audit(db, "auth.login_failed", email=email, journal=JOURNAL_AUTH, request=request,
              details={"reason": "unknown_user"})
        db.commit()
        raise invalid
    now = utcnow()
    locked_until = user.locked_until
    if locked_until is not None and locked_until.tzinfo is None:
        locked_until = locked_until.replace(tzinfo=UTC)
    if locked_until is not None and locked_until > now:
        minutes = int((locked_until - now).total_seconds() // 60) + 1
        raise HTTPException(
            status.HTTP_423_LOCKED,
            f"Вход временно заблокирован из-за неудачных попыток. Повторите через {minutes} мин.",
        )
    if not verify_password(data.password, user.password_hash):
        user.failed_logins += 1
        details = {"reason": "bad_password", "attempt": user.failed_logins}
        if user.failed_logins >= settings.max_failed_logins:
            user.locked_until = now + timedelta(minutes=settings.lockout_minutes)
            user.failed_logins = 0
            details["locked"] = True
            notify(db, user, "security", "Вход в учетную запись заблокирован",
                   f"Зафиксировано {settings.max_failed_logins} неудачных попыток входа. "
                   f"Вход заблокирован на {settings.lockout_minutes} минут.", important=True)
        audit(db, "auth.login_failed", user=user, journal=JOURNAL_AUTH, request=request,
              details=details)
        db.commit()  # счетчик попыток сохраняется несмотря на ошибку ответа
        raise invalid
    if user.is_blocked:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Учетная запись заблокирована администратором")
    if not user.email_confirmed:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Адрес электронной почты не подтвержден")
    if user.totp_enabled:
        if not data.totp_code:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Требуется код двухфакторной аутентификации",
                                headers={"X-2FA-Required": "true"})
        if not verify_totp(user.totp_secret or "", data.totp_code):
            audit(db, "auth.login_failed", user=user, journal=JOURNAL_AUTH, request=request,
                  details={"reason": "bad_totp"})
            db.commit()
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Неверный код двухфакторной аутентификации")
    user.failed_logins, user.locked_until, user.last_login_at = 0, None, now
    audit(db, "auth.login", user=user, journal=JOURNAL_AUTH, request=request)
    return _tokens(db, user)


@router.post("/refresh", response_model=TokenOut)
def refresh(data: RefreshIn, db: Session = Depends(get_db)):
    user = consume_token(db, data.refresh_token, "refresh")
    if user is None or user.is_blocked or user.deleted_at is not None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Сеанс завершен. Выполните вход повторно")
    return _tokens(db, user)


@router.post("/logout", response_model=Message)
def logout(request: Request, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    revoke_tokens(db, user, "refresh")
    audit(db, "auth.logout", user=user, journal=JOURNAL_AUTH, request=request)
    return Message(message="Выход выполнен")


@router.post("/password/forgot", response_model=Message)
def forgot_password(data: ForgotIn, request: Request, db: Session = Depends(get_db)):
    """Отправляет временную ссылку для восстановления пароля."""
    settings = get_settings()
    user = db.query(User).filter(User.email == data.email.lower(), User.deleted_at.is_(None)).first()
    if user is not None and not user.is_blocked:
        token = issue_token(db, user, "password_reset",
                            timedelta(minutes=settings.password_reset_minutes))
        enqueue(db, "send_email", to=user.email, subject="Восстановление пароля",
                body=f"Для смены пароля используйте код: {token}\n"
                     f"Код действителен {settings.password_reset_minutes} минут.")
        audit(db, "auth.password_reset_requested", user=user, journal=JOURNAL_AUTH, request=request)
    # Ответ одинаков независимо от существования адреса (защита от перебора адресов)
    return Message(message="Если адрес зарегистрирован, на него отправлена ссылка для восстановления пароля")


@router.post("/password/reset", response_model=Message)
def reset_password(data: ResetIn, request: Request, db: Session = Depends(get_db)):
    validate_password_strength(data.new_password)
    user = consume_token(db, data.token, "password_reset")
    if user is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Ссылка недействительна или устарела")
    user.password_hash = hash_password(data.new_password)
    user.failed_logins, user.locked_until = 0, None
    revoke_tokens(db, user, "refresh")
    audit(db, "auth.password_reset", user=user, journal=JOURNAL_AUTH, request=request)
    return Message(message="Пароль изменен")


@router.post("/password/change", response_model=Message)
def change_password(data: ChangePasswordIn, request: Request,
                    user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    if not verify_password(data.current_password, user.password_hash):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Текущий пароль указан неверно")
    validate_password_strength(data.new_password)
    user.password_hash = hash_password(data.new_password)
    audit(db, "auth.password_changed", user=user, journal=JOURNAL_AUTH, request=request)
    return Message(message="Пароль изменен")


@router.post("/2fa/setup")
def setup_2fa(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    """Создает секрет двухфакторной аутентификации для приложения-аутентификатора."""
    if user.totp_enabled:
        raise HTTPException(status.HTTP_409_CONFLICT, "Двухфакторная аутентификация уже включена")
    user.totp_secret = new_totp_secret()
    return {"secret": user.totp_secret, "uri": totp_uri(user, user.totp_secret)}


@router.post("/2fa/enable", response_model=Message)
def enable_2fa(data: TotpCodeIn, request: Request, user: User = Depends(get_current_user),
               db: Session = Depends(get_db)):
    if not user.totp_secret or not verify_totp(user.totp_secret, data.code):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Неверный код подтверждения")
    user.totp_enabled = True
    audit(db, "auth.2fa_enabled", user=user, journal=JOURNAL_AUTH, request=request)
    return Message(message="Двухфакторная аутентификация включена")


@router.post("/2fa/disable", response_model=Message)
def disable_2fa(data: TotpCodeIn, request: Request, user: User = Depends(get_current_user),
                db: Session = Depends(get_db)):
    if not user.totp_enabled or not verify_totp(user.totp_secret or "", data.code):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Неверный код подтверждения")
    user.totp_enabled, user.totp_secret = False, None
    audit(db, "auth.2fa_disabled", user=user, journal=JOURNAL_AUTH, request=request)
    return Message(message="Двухфакторная аутентификация отключена")
