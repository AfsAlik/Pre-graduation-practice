"""Подсистема администрирования и безопасности: регистрация и аутентификация."""
import re
import uuid

import pyotp

from app.main import API_PREFIX
from app.models import User
from app.services import notifier
from tests.conftest import PASSWORD


def _email() -> str:
    return f"new-{uuid.uuid4().hex[:10]}@example.com"


def _register(client, email, consent=True, password=PASSWORD):
    return client.post(f"{API_PREFIX}/auth/register", json={
        "email": email, "password": password, "display_name": "Тест",
        "consent_personal_data": consent})


def _login(client, email, password=PASSWORD, **extra):
    return client.post(f"{API_PREFIX}/auth/login", json={"email": email, "password": password, **extra})


def _token_from_outbox(pattern: str) -> str:
    for message in reversed(notifier.outbox):
        match = re.search(pattern, message.get("body", ""))
        if match:
            return match.group(1)
    raise AssertionError("Письмо с токеном не найдено")


def test_registration_requires_consent(client):
    response = _register(client, _email(), consent=False)
    assert response.status_code == 422
    assert "согласие" in response.json()["detail"]


def test_registration_confirmation_and_login(client):
    email = _email()
    assert _register(client, email).status_code == 201
    # До подтверждения адреса вход невозможен
    assert _login(client, email).status_code == 403
    token = _token_from_outbox(r"confirm-email\?token=([\w\-]+)")
    assert client.get(f"{API_PREFIX}/auth/confirm-email", params={"token": token}).status_code == 200
    # Ссылка одноразовая
    assert client.get(f"{API_PREFIX}/auth/confirm-email", params={"token": token}).status_code == 400
    tokens = _login(client, email).json()
    assert tokens["role"] == "user" and tokens["access_token"] and tokens["refresh_token"]
    # Обновление токена
    refreshed = client.post(f"{API_PREFIX}/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert refreshed.status_code == 200
    # Повторное использование refresh-токена запрещено
    again = client.post(f"{API_PREFIX}/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert again.status_code == 401


def test_duplicate_email_and_weak_password(client):
    email = _email()
    assert _register(client, email).status_code == 201
    assert _register(client, email).status_code == 409
    assert _register(client, _email(), password="12345678").status_code == 422


def test_password_is_stored_as_hash(api, db):
    user = db.get(User, api.user_id)
    assert PASSWORD not in user.password_hash
    assert user.password_hash.startswith("$argon2")


def test_lockout_after_five_failed_attempts(client, make_api):
    api = make_api()
    for _ in range(4):
        assert _login(client, api.email, "wrong-password1").status_code == 401
    assert _login(client, api.email, "wrong-password1").status_code == 401  # пятая попытка
    locked = _login(client, api.email)  # верный пароль, но вход заблокирован
    assert locked.status_code == 423
    assert "заблокирован" in locked.json()["detail"]
    notifications = api.get("/notifications")["items"]
    assert any(n["kind"] == "security" for n in notifications)


def test_password_recovery_by_temporary_token(client, make_api):
    api = make_api()
    response = client.post(f"{API_PREFIX}/auth/password/forgot", json={"email": api.email})
    assert response.status_code == 200
    token = _token_from_outbox(r"код: ([\w\-]+)")
    new_password = "New-pass-2027"
    reset = client.post(f"{API_PREFIX}/auth/password/reset",
                        json={"token": token, "new_password": new_password})
    assert reset.status_code == 200
    assert _login(client, api.email).status_code == 401
    assert _login(client, api.email, new_password).status_code == 200
    # Для неизвестного адреса ответ тот же (адреса нельзя перебрать)
    unknown = client.post(f"{API_PREFIX}/auth/password/forgot", json={"email": _email()})
    assert unknown.status_code == 200 and unknown.json() == response.json()


def test_two_factor_authentication(client, make_api):
    api = make_api()
    setup = api.post("/auth/2fa/setup")
    totp = pyotp.TOTP(setup["secret"])
    api.post("/auth/2fa/enable", {"code": "000000"}, expected=400)
    api.post("/auth/2fa/enable", {"code": totp.now()})
    without_code = _login(client, api.email)
    assert without_code.status_code == 401 and without_code.headers.get("X-2FA-Required") == "true"
    assert _login(client, api.email, totp_code="123456").status_code == 401
    assert _login(client, api.email, totp_code=totp.now()).status_code == 200


def test_role_based_access(make_api):
    user, analyst, admin = make_api("user"), make_api("analyst"), make_api("admin")
    # Пользователь не имеет доступа к административным разделам
    for path in ("/admin/users", "/admin/logs", "/admin/monitoring", "/admin/settings",
                 "/ml/quality", "/ml/models"):
        user.get(path, expected=403)
    # Аналитик видит качество моделей, но не управление учетными записями
    analyst.get("/ml/quality")
    analyst.get("/admin/users", expected=403)
    # Администратор имеет доступ ко всем разделам
    admin.get("/ml/quality")
    admin.get("/admin/users")


def test_users_see_only_their_own_data(make_api):
    first, second = make_api(), make_api()
    account = first.account()
    transaction = first.spend(account["id"], 100, "Продукты")
    assert second.get("/accounts") == []
    second.put(f"/transactions/{transaction['id']}", {"kind": "expense", "amount": "1",
               "account_id": account["id"]}, expected=404)
    second.delete(f"/transactions/{transaction['id']}", expected=404)


def test_logout_revokes_refresh_token(client, make_api):
    api = make_api()
    tokens = _login(client, api.email).json()
    client.post(f"{API_PREFIX}/auth/logout", headers={"Authorization": f"Bearer {tokens['access_token']}"})
    response = client.post(f"{API_PREFIX}/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert response.status_code == 401
