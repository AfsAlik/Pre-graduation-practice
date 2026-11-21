"""Аналитика и отчетность, импорт и экспорт данных (п. 4.2.1.4, 4.2.1.7 ТЗ)."""
from datetime import date
from decimal import Decimal
from urllib.parse import quote

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import Response
from sqlalchemy.orm import Session

from ..audit import audit
from ..db import get_db
from ..models import Account, Transaction, User
from ..security import get_current_user
from ..services import entities, exporters, importers, reports
from ..services.budget import check_budgets_for_category
from ..services.categories import category_map
from ..services.currency import get_rate, list_currencies, rates_info, refresh_rates
from ..services.ml_registry import suggest_categories
from ..services.periods import period_bounds

router = APIRouter()
EXPORT_LIMIT = 20000


def _period(start: date | None, end: date | None) -> tuple[date, date]:
    month_start, month_end = period_bounds("month")
    start, end = start or month_start, end or month_end
    if start > end:
        raise HTTPException(422, "Дата начала периода не может быть позже даты окончания")
    return start, end


def _file(content: bytes, fmt: str, name: str) -> Response:
    return Response(
        content, media_type=exporters.CONTENT_TYPES[fmt],
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(name)}.{fmt}"},
    )


@router.get("/dashboard", tags=["Аналитика"])
def dashboard(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    """Главный экран: баланс, доходы и расходы, график, бюджет, цель, портфель, прогноз."""
    return reports.dashboard(db, user)


@router.get("/reports", tags=["Аналитика"])
def report(
    start: date | None = None, end: date | None = None,
    account_id: list[str] | None = Query(default=None),
    category_id: list[str] | None = Query(default=None),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> dict:
    """Аналитический отчет за период с фильтрацией по счетам и категориям."""
    start, end = _period(start, end)
    return reports.build_report(db, user, start, end, account_ids=account_id,
                                category_ids=category_id)


@router.get("/reports/export", tags=["Импорт и экспорт"])
def export_report(
    fmt: str = Query(default="pdf", alias="format", pattern="^(pdf|xlsx|csv)$"),
    start: date | None = None, end: date | None = None,
    account_id: list[str] | None = Query(default=None),
    category_id: list[str] | None = Query(default=None),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
):
    """Экспорт отчета по категориям в PDF, XLSX или CSV."""
    start, end = _period(start, end)
    data = reports.build_report(db, user, start, end, account_ids=account_id,
                                category_ids=category_id)
    rows = [[row["name"], Decimal(row["amount"]), f"{row['share']} %"]
            for row in data["expenses_by_category"]]
    title = f"Отчет по расходам за {start.strftime('%d.%m.%Y')} – {end.strftime('%d.%m.%Y')}"
    summary = [("Доходы", f"{data['income']} {data['currency']}"),
               ("Расходы", f"{data['expense']} {data['currency']}"),
               ("Сводка", data["summary"])]
    content = exporters.render(fmt, rows, title, summary, ["Категория", "Сумма", "Доля"])
    return _file(content, fmt, f"Отчет_{start}_{end}")


@router.get("/transactions/export", tags=["Импорт и экспорт"])
def export_transactions(
    request: Request,
    fmt: str = Query(default="xlsx", alias="format", pattern="^(pdf|xlsx|csv)$"),
    start: date | None = None, end: date | None = None,
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
):
    """Экспорт истории операций за период в PDF, XLSX или CSV."""
    start, end = _period(start, end)
    items = reports.load_transactions(db, user, start, end)[:EXPORT_LIMIT]
    accounts = {a.id: a for a in db.query(Account).filter(Account.user_id == user.id)}
    rows = exporters.transaction_rows(items, accounts, category_map(db, user.id))
    income, expense = reports.totals(db, user, items)
    title = f"История операций за {start.strftime('%d.%m.%Y')} – {end.strftime('%d.%m.%Y')}"
    summary = [("Операций", str(len(rows))), ("Доходы", f"{income} {user.base_currency}"),
               ("Расходы", f"{expense} {user.base_currency}")]
    audit(db, "export.transactions", user=user, request=request,
          details={"format": fmt, "count": len(rows)})
    return _file(exporters.render(fmt, rows, title, summary), fmt, f"Операции_{start}_{end}")


@router.post("/import/statement", tags=["Импорт и экспорт"])
async def import_statement(
    request: Request, account_id: str = Form(...), dry_run: bool = Form(default=False),
    file: UploadFile = File(...),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> dict:
    """Импорт транзакций из банковской выписки CSV, OFX или QIF.

    Поля выписки распознаются автоматически, импортированные операции
    категоризируются. Повторный импорт той же выписки не создает дубликатов.
    При dry_run=true возвращается предварительный просмотр без сохранения.
    """
    account = entities.get_owned(db, user, "account", account_id)
    content = await file.read()
    if len(content) > 5 * 1024 * 1024:
        raise HTTPException(413, "Размер файла выписки не должен превышать 5 МБ")
    try:
        operations = importers.parse_statement(file.filename or "", content)
    except importers.ImportError_ as exc:
        raise HTTPException(422, str(exc)) from exc
    known = {
        row[0] for row in db.query(Transaction.external_id).filter(
            Transaction.user_id == user.id, Transaction.external_id.is_not(None))
    }
    fresh = []
    for operation in operations:
        fingerprint = operation.fingerprint()
        if fingerprint not in known:
            known.add(fingerprint)
            fresh.append((operation, fingerprint))
    skipped = len(operations) - len(fresh)
    # Категории всех операций выписки определяются одним обращением к модели
    suggestions = suggest_categories(db, user.id, [
        (operation.description, "income" if operation.amount > 0 else "expense")
        for operation, _ in fresh])
    imported, preview, categories = 0, [], set()
    for (operation, fingerprint), suggestion in zip(fresh, suggestions, strict=True):
        data = {
            "kind": "income" if operation.amount > 0 else "expense",
            "amount": abs(operation.amount), "account_id": account.id,
            "currency": operation.currency or account.currency,
            "occurred_at": operation.occurred_at, "merchant": operation.description[:200],
            "source": "import",
        }
        if dry_run:
            preview.append({**data, "amount": str(data["amount"]),
                            "occurred_at": operation.occurred_at.isoformat()})
            imported += 1
            continue
        transaction = entities.save_entity(db, user, "transaction", None, data,
                                           suggestion=suggestion, bulk=True)
        transaction.external_id = fingerprint
        categories.add(transaction.category_id)
        imported += 1
    for category_id in categories:
        check_budgets_for_category(db, user, category_id)
    if not dry_run:
        audit(db, "import.statement", user=user, request=request,
              details={"file": file.filename, "imported": imported, "skipped": skipped})
    return {"total": len(operations), "imported": imported, "skipped_duplicates": skipped,
            "dry_run": dry_run, "preview": preview[:50]}


@router.get("/rates", tags=["Справочники"])
def rates(refresh: bool = False, user: User = Depends(get_current_user),
          db: Session = Depends(get_db)) -> dict:
    """Курсы валют к рублю с датой актуальности."""
    info = refresh_rates(db) if refresh else rates_info(db)
    values = {}
    for code in list_currencies(db):
        try:
            values[code] = str(get_rate(db, code))
        except Exception:
            continue
    return {"base": "RUB", "rates": values, **info}
