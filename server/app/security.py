"""Аутентификация, авторизация и криптографические функции.

Пароли хранятся только в виде результата необратимого хеширования Argon2 с солью
(п. 4.4.4.1 в, 4.4.7.3 ТЗ). Для доступа к программному интерфейсу используются
JWT-токены. Разграничение доступа выполняется по ролям (п. 4.2.1.8 д).
"""
import hashlib
import secrets
from datetime import UTC, datetime, timedelta

import jwt
import pyotp
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from .config import get_settings
from .db import get_db
from .models import AuthToken, User, utcnow

ROLE_USER = "user"
ROLE_ANALYST = "analyst"
ROLE_ADMIN = "admin"
ROLES = (ROLE_USER, ROLE_ANALYST, ROLE_ADMIN)
ROLE_TITLES = {ROLE_USER: "Пользователь", ROLE_ANALYST: "Аналитик", ROLE_ADMIN: "Администратор"}

_hasher = PasswordHasher()
_bearer = HTTPBearer(auto_error=False)
JWT_ALGORITHM = "HS256"


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return _hasher.verify(password_hash, password)
    except (VerificationError, InvalidHashError):
        return False


def validate_password_strength(password: str) -> None:
    """Минимальные требования к паролю: длина, буквы и цифры."""
    if len(password) < 8 or password.isdigit() or password.isalpha():
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "Пароль должен содержать не менее 8 символов, включая буквы и цифры",
        )


def create_access_token(user: User) -> str:
    settings = get_settings()
    now = datetime.now(UTC)
    payload = {
        "sub": user.id,
        "role": user.role,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(minutes=settings.access_token_minutes)).timestamp()),
        "typ": "access",
    }
    return jwt.encode(payload, settings.secret_key, algorithm=JWT_ALGORITHM)


def _digest(raw: str) -> str:
    return hashlib.sha256(raw.encode()).hexdigest()


def issue_token(db: Session, user: User, kind: str, lifetime: timedelta) -> str:
    """Выпускает одноразовый или refresh-токен; в БД хранится только его хеш."""
    raw = secrets.token_urlsafe(32)
    db.add(
        AuthToken(
            user_id=user.id, kind=kind, token_hash=_digest(raw), expires_at=utcnow() + lifetime
        )
    )
    return raw


def consume_token(db: Session, raw: str, kind: str, *, keep: bool = False) -> User | None:
    """Проверяет токен и помечает его использованным (кроме keep=True)."""
    token = (
        db.query(AuthToken)
        .filter(AuthToken.token_hash == _digest(raw), AuthToken.kind == kind)
        .first()
    )
    if token is None or token.used_at is not None:
        return None
    expires = token.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=UTC)
    if expires < utcnow():
        return None
    if not keep:
        token.used_at = utcnow()
    return db.get(User, token.user_id)


def revoke_tokens(db: Session, user: User, kind: str) -> None:
    db.query(AuthToken).filter(
        AuthToken.user_id == user.id, AuthToken.kind == kind, AuthToken.used_at.is_(None)
    ).update({"used_at": utcnow()})


def new_totp_secret() -> str:
    return pyotp.random_base32()


def totp_uri(user: User, secret: str) -> str:
    return pyotp.TOTP(secret).provisioning_uri(name=user.email, issuer_name="Финансовый помощник")


def verify_totp(secret: str, code: str) -> bool:
    return bool(code) and pyotp.TOTP(secret).verify(code, valid_window=1)


def get_current_user(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    db: Session = Depends(get_db),
) -> User:
    unauthorized = HTTPException(
        status.HTTP_401_UNAUTHORIZED,
        "Требуется вход в систему",
        headers={"WWW-Authenticate": "Bearer"},
    )
    if credentials is None:
        raise unauthorized
    try:
        payload = jwt.decode(
            credentials.credentials, get_settings().secret_key, algorithms=[JWT_ALGORITHM]
        )
    except jwt.PyJWTError:
        raise unauthorized from None
    if payload.get("typ") != "access":
        raise unauthorized
    user = db.get(User, payload.get("sub"))
    if user is None or user.deleted_at is not None:
        raise unauthorized
    if user.is_blocked:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Учетная запись заблокирована")
    request.state.user = user
    return user


def require_roles(*roles: str):
    """Зависимость FastAPI: доступ только пользователям с указанными ролями."""

    def checker(user: User = Depends(get_current_user)) -> User:
        if user.role not in roles:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Недостаточно прав доступа")
        return user

    return checker


require_analyst = require_roles(ROLE_ANALYST, ROLE_ADMIN)
require_admin = require_roles(ROLE_ADMIN)
