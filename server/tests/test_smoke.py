"""Проверка запуска приложения и основных служебных запросов."""
from app.main import API_PREFIX


def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}
    assert client.get("/health/ready").json()["database"] is True


def test_openapi_is_generated(client):
    schema = client.get("/openapi.json").json()
    assert f"{API_PREFIX}/transactions" in schema["paths"]
    assert f"{API_PREFIX}/sync" in schema["paths"]


def test_access_requires_authentication(client):
    assert client.get(f"{API_PREFIX}/accounts").status_code == 401


def test_first_transaction(api):
    account = api.account(initial="1000")
    transaction = api.spend(account["id"], "250.50", merchant="Пятерочка")
    assert transaction["auto_categorized"] is True
    assert transaction["category_id"] == api.category_id("Продукты")
    accounts = api.get("/accounts")
    assert accounts[0]["balance"] == "749.50"
    dashboard = api.get("/dashboard")
    assert dashboard["total_balance"] == "749.50"
    assert dashboard["expense"] == "250.50"
