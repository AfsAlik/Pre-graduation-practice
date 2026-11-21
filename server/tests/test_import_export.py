"""Подсистема интеграции и импорта/экспорта данных (п. 4.2.1.7 ТЗ, п. 4.1.11 ПМИ)."""
import io
from datetime import date
from decimal import Decimal

import httpx
import openpyxl
import pytest

from app.config import get_settings
from app.services import currency, importers, quotes

CSV = (
    "Дата операции;Описание;Сумма операции;Валюта\n"
    "02.11.2026 10:15;PYATEROCHKA 1234 EKATERINBURG;-1 250,50;RUB\n"
    "03.11.2026 19:40;YANDEX TAXI;-380,00;RUB\n"
    "05.11.2026 09:00;Зачисление заработной платы;85 000,00;RUB\n"
    "07.11.2026 13:05;APTEKA RIGLA;-642,30;RUB\n"
).encode("cp1251")

OFX = b"""OFXHEADER:100
DATA:OFXSGML
<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>RUB
<BANKTRANLIST>
<STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261102101500<TRNAMT>-1250.50<FITID>A1
<NAME>PYATEROCHKA 1234<MEMO>EKATERINBURG</STMTTRN>
<STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261103194000<TRNAMT>-380.00<FITID>A2<NAME>YANDEX TAXI</STMTTRN>
<STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261105<TRNAMT>85000.00<FITID>A3<NAME>SALARY</STMTTRN>
</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
"""

QIF = """!Type:Bank
D02/11/2026
T-1250.50
PPYATEROCHKA 1234
MEKATERINBURG
^
D03/11/2026
T-380.00
PYANDEX TAXI
^
D05/11/2026
T85,000.00
PЗачисление заработной платы
^
""".encode()


def _upload(api, name, content, account_id, dry_run=False, expected=200):
    return api.request("POST", "/import/statement", expected,
                       data={"account_id": account_id, "dry_run": str(dry_run).lower()},
                       files={"file": (name, content, "application/octet-stream")}).json()


def test_parsers_recognize_fields():
    rows = importers.parse_csv(CSV)
    assert [str(r.amount) for r in rows] == ["-1250.50", "-380.00", "85000.00", "-642.30"]
    assert rows[0].occurred_at.strftime("%Y-%m-%d %H:%M") == "2026-11-02 10:15"
    assert rows[0].description == "PYATEROCHKA 1234 EKATERINBURG" and rows[0].currency == "RUB"
    ofx = importers.parse_ofx(OFX)
    assert [(str(r.amount), r.external_id) for r in ofx] == [("-1250.50", "A1"), ("-380.00", "A2"),
                                                             ("85000.00", "A3")]
    assert ofx[0].description == "PYATEROCHKA 1234 EKATERINBURG" and ofx[0].currency == "RUB"
    qif = importers.parse_qif(QIF)
    assert [str(r.amount) for r in qif] == ["-1250.50", "-380.00", "85000.00"]
    assert qif[0].occurred_at.date() == date(2026, 11, 2)
    with pytest.raises(importers.ImportError_):
        importers.parse_statement("statement.docx", b"\x00\x01\x02")


@pytest.mark.parametrize(("name", "content", "count"), [
    ("statement.csv", CSV, 4), ("statement.ofx", OFX, 3), ("statement.qif", QIF, 3)])
def test_statement_import_matches_source_file(api, name, content, count):
    account = api.account(initial="0")
    preview = _upload(api, name, content, account["id"], dry_run=True)
    assert preview["imported"] == count and api.get("/transactions")["total"] == 0
    result = _upload(api, name, content, account["id"])
    assert result == {**result, "total": count, "imported": count, "skipped_duplicates": 0}
    items = sorted(api.get("/transactions")["items"], key=lambda t: t["occurred_at"])
    assert [(t["kind"], t["amount"]) for t in items[:3]] == [
        ("expense", "1250.50"), ("expense", "380.00"), ("income", "85000.00")]
    assert items[0]["occurred_at"].startswith("2026-11-02") and items[0]["source"] == "import"
    assert "PYATEROCHKA" in items[0]["merchant"]
    # Автоматическая категоризация импортированных операций
    assert items[0]["category_id"] == api.category_id("Продукты")
    assert items[1]["category_id"] == api.category_id("Транспорт")
    # Повторный импорт той же выписки не создает дубликатов
    again = _upload(api, name, content, account["id"])
    assert again["imported"] == 0 and again["skipped_duplicates"] == count
    assert api.get("/transactions")["total"] == count


def test_import_rejects_unsupported_file(api):
    account = api.account()
    _upload(api, "photo.png", b"\x89PNG\r\n\x1a\n\x00\x00", account["id"], expected=422)
    _upload(api, "empty.csv", "Дата;Сумма\n".encode(), account["id"], expected=422)


def test_export_transactions_to_pdf_xlsx_csv(api):
    account = api.account("Основная карта", initial="100000")
    api.spend(account["id"], "1250.50", "Продукты", merchant="Пятерочка")
    api.spend(account["id"], "380", "Транспорт", merchant="Яндекс Такси")
    api.earn(account["id"], "85000")
    xlsx = api.request("GET", "/transactions/export", 200, params={"format": "xlsx"})
    assert xlsx.headers["content-type"].startswith("application/vnd.openxmlformats")
    sheet = openpyxl.load_workbook(io.BytesIO(xlsx.content)).active
    rows = list(sheet.iter_rows(values_only=True))
    header = next(i for i, r in enumerate(rows) if r[0] == "Дата")
    data = rows[header + 1:]
    assert len(data) == 3
    assert sorted(float(r[4]) for r in data) == [-1250.5, -380.0, 85000.0]
    assert {r[2] for r in data} == {"Продукты", "Транспорт", "Зарплата"}
    assert all(r[3] == "Основная карта" for r in data)

    pdf = api.request("GET", "/transactions/export", 200, params={"format": "pdf"})
    assert pdf.headers["content-type"] == "application/pdf" and pdf.content.startswith(b"%PDF")
    assert len(pdf.content) > 2000
    csv = api.request("GET", "/transactions/export", 200, params={"format": "csv"})
    text = csv.content.decode("utf-8-sig")
    assert text.count("\n") == 4 and "Пятерочка" in text
    report = api.request("GET", "/reports/export", 200, params={"format": "pdf"})
    assert report.content.startswith(b"%PDF")
    api.request("GET", "/transactions/export", 422, params={"format": "docx"})


def test_cbr_response_is_parsed():
    xml = ('<?xml version="1.0" encoding="windows-1251"?><ValCurs Date="21.11.2026" name="x">'
           '<Valute ID="R01235"><NumCode>840</NumCode><CharCode>USD</CharCode><Nominal>1</Nominal>'
           '<Name>USD</Name><Value>91,2345</Value></Valute>'
           '<Valute ID="R01335"><NumCode>398</NumCode><CharCode>KZT</CharCode><Nominal>100</Nominal>'
           '<Name>KZT</Name><Value>18,5000</Value></Valute></ValCurs>').encode("cp1251")
    day, rates = currency.parse_cbr_xml(xml)
    assert day == date(2026, 11, 21)
    assert rates == {"USD": Decimal("91.234500"), "KZT": Decimal("0.185000")}


def test_saved_rates_are_used_when_source_is_unavailable(api, db, monkeypatch):
    before = api.get("/rates")
    assert before["rates"]["USD"] == "90.000000" and before["as_of"] is not None
    settings = get_settings()
    monkeypatch.setattr(settings, "rates_provider", "cbr")

    def unavailable(*args, **kwargs):
        raise httpx.ConnectError("источник недоступен")

    monkeypatch.setattr(httpx, "get", unavailable)
    result = currency.refresh_rates(db)
    assert result["ok"] is False and result["as_of"] == before["as_of"]
    after = api.get("/rates", params={"refresh": True})
    assert after["rates"]["USD"] == "90.000000"  # последние сохраненные значения
    assert after["as_of"] == before["as_of"] and after["ok"] is False
    assert currency.convert(db, Decimal("10"), "USD", "RUB") == Decimal("900.00")


def test_saved_quote_is_used_when_source_is_unavailable(api, db, monkeypatch):
    api.post("/investments/trades", {"asset_type": "stock", "ticker": "LKOH", "quantity": "1",
                                     "price": "6500"})
    first = api.get("/investments/portfolio")["positions"][0]
    assert Decimal(first["current_price"]) == Decimal("7000")

    def unavailable(*args, **kwargs):
        raise quotes.QuoteUnavailable("источник недоступен")

    monkeypatch.setattr(quotes, "fetch_quote", unavailable)
    assert quotes.refresh_user_quotes(db, api.user_id, force=True) == 0
    second = api.get("/investments/portfolio")["positions"][0]
    assert second["current_price"] == first["current_price"]
    assert second["price_as_of"] == first["price_as_of"]  # дата актуальности сохраненной цены


def test_bank_connection_requires_explicit_consent(api):
    api.post("/bank-connections", {"provider": "demo-bank"}, expected=403)
    api.post("/bank-consents", {"provider": "demo-bank"}, expected=201)
    assert api.post("/bank-connections", {"provider": "demo-bank"})["provider"] == "demo-bank"
    assert len(api.get("/bank-consents")["items"]) == 1
    api.delete("/bank-consents/demo-bank")  # отзыв согласия
    api.post("/bank-connections", {"provider": "demo-bank"}, expected=403)
