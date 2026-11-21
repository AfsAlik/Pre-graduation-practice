"""Общие средства автоматических тестов серверной части.

По умолчанию тесты выполняются на СУБД SQLite во временном файле и не требуют
установленного PostgreSQL. Для проверки на PostgreSQL задайте переменную
FH_TEST_DATABASE_URL, например:
    FH_TEST_DATABASE_URL=postgresql+psycopg://finhelper:finhelper@localhost/finhelper_test
"""
import os
import tempfile
import uuid

_tmp = tempfile.mkdtemp(prefix="finhelper-test-")
os.environ["FH_ENVIRONMENT"] = "test"
os.environ["FH_DATABASE_URL"] = os.getenv("FH_TEST_DATABASE_URL", f"sqlite:///{_tmp}/test.db")
os.environ["FH_REDIS_URL"] = ""
os.environ["FH_ML_SERVICE_URL"] = ""
os.environ["FH_ML_MODELS_DIR"] = f"{_tmp}/models"
os.environ["FH_STORAGE_DIR"] = f"{_tmp}/storage"
os.environ["FH_RATES_PROVIDER"] = "static"
os.environ["FH_QUOTES_PROVIDER"] = "static"
os.environ["FH_PUSH_PROVIDER"] = "log"
os.environ["FH_EMAIL_PROVIDER"] = "log"
os.environ["FH_SECRET_KEY"] = "test-secret-key-for-automated-tests-only"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import bootstrap  # noqa: E402
from app.db import Base, SessionLocal, engine  # noqa: E402
from app.main import API_PREFIX, app  # noqa: E402
from app.services import notifier, tasks  # noqa: E402

PASSWORD = "Test-pass-2026"


@pytest.fixture(scope="session", autouse=True)
def prepared_database():
    """Чистая схема БД, справочники, курсы валют и обученная модель категоризации."""
    tasks.run_inline = True
    Base.metadata.drop_all(engine)
    bootstrap.init_db(create_tables=True)
    yield
    Base.metadata.drop_all(engine)


@pytest.fixture()
def db():
    session = SessionLocal()
    try:
        yield session
        session.commit()
    finally:
        session.close()


class Api:
    """Клиент программного интерфейса от имени одного пользователя."""

    def __init__(self, client: TestClient, headers: dict, email: str, user_id: str):
        self.client, self.headers, self.email, self.user_id = client, headers, email, user_id

    def request(self, method: str, path: str, expected: int | None = None, **kwargs):
        response = self.client.request(method, API_PREFIX + path, headers=self.headers, **kwargs)
        if expected is not None:
            detail = f"{method} {path}: {response.status_code} {response.text}"
            assert response.status_code == expected, detail
        return response

    def get(self, path, expected=200, **kwargs):
        return self.request("GET", path, expected, **kwargs).json()

    def post(self, path, json=None, expected=None, **kwargs):
        response = self.request("POST", path, expected, json=json, **kwargs)
        if expected is None:
            detail = f"POST {path}: {response.status_code} {response.text}"
            assert response.status_code in (200, 201, 202), detail
        return response.json()

    def put(self, path, json=None, expected=200, **kwargs):
        return self.request("PUT", path, expected, json=json, **kwargs).json()

    def patch(self, path, json=None, expected=200, **kwargs):
        return self.request("PATCH", path, expected, json=json, **kwargs).json()

    def delete(self, path, expected=200, **kwargs):
        return self.request("DELETE", path, expected, **kwargs).json()

    # --- сокращения для подготовки данных ---
    def account(self, name="Карта", type="bank", currency="RUB", initial="0"):
        return self.post("/accounts", {"name": name, "type": type, "currency": currency,
                                       "initial_balance": initial})

    def category_id(self, name: str) -> str:
        return next(c["id"] for c in self.get("/categories") if c["name"] == name)

    def spend(self, account_id, amount, category=None, **extra):
        data = {"kind": "expense", "amount": str(amount), "account_id": account_id, **extra}
        if category:
            data["category_id"] = self.category_id(category)
        return self.post("/transactions", data)

    def earn(self, account_id, amount, category="Зарплата", **extra):
        data = {"kind": "income", "amount": str(amount), "account_id": account_id,
                "category_id": self.category_id(category), **extra}
        return self.post("/transactions", data)


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture()
def make_api(client):
    """Создает пользователя с указанной ролью и возвращает клиент от его имени."""

    def factory(role: str = "user") -> Api:
        email = f"{role}-{uuid.uuid4().hex[:10]}@example.com"
        session = SessionLocal()
        try:
            user = bootstrap.create_user(session, email, PASSWORD, role)
            session.commit()
            user_id = user.id
        finally:
            session.close()
        response = client.post(f"{API_PREFIX}/auth/login", json={"email": email, "password": PASSWORD})
        assert response.status_code == 200, response.text
        headers = {"Authorization": f"Bearer {response.json()['access_token']}"}
        return Api(client, headers, email, user_id)

    return factory


@pytest.fixture()
def api(make_api) -> Api:
    return make_api("user")


@pytest.fixture(autouse=True)
def clean_outbox():
    notifier.outbox.clear()
    yield
