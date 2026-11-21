"""Импорт транзакций из банковских выписок CSV, OFX и QIF (п. 4.2.1.7 ТЗ)."""
import csv
import hashlib
import io
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation


@dataclass
class ParsedOperation:
    """Операция выписки. Сумма со знаком: расход отрицательный, доход положительный."""

    occurred_at: datetime
    amount: Decimal
    description: str
    currency: str | None = None
    external_id: str = ""

    def fingerprint(self) -> str:
        """Отпечаток операции для защиты от повторного импорта."""
        if self.external_id:
            return hashlib.sha256(f"id:{self.external_id}".encode()).hexdigest()
        raw = f"{self.occurred_at.date()}|{self.amount}|{self.description.strip().lower()}"
        return hashlib.sha256(raw.encode()).hexdigest()


class ImportError_(ValueError):
    """Файл выписки не удалось разобрать."""


_DATE_FORMATS = [
    "%d.%m.%Y %H:%M:%S", "%d.%m.%Y %H:%M", "%d.%m.%Y", "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%d", "%d/%m/%Y", "%m/%d/%Y", "%d.%m.%y", "%d/%m/%y", "%d-%m-%Y",
]
_COLUMNS = {
    "date": ["дата операции", "дата платежа", "дата", "date", "posted", "transaction date"],
    "amount": ["сумма операции", "сумма платежа", "сумма в валюте счета", "сумма", "amount", "sum"],
    "debit": ["расход", "списание", "дебет", "debit", "withdrawal"],
    "credit": ["приход", "зачисление", "кредит", "credit", "deposit"],
    "description": ["описание", "назначение платежа", "назначение", "description", "details",
                    "memo", "merchant", "торговая точка", "получатель", "payee", "name"],
    "currency": ["валюта операции", "валюта платежа", "валюта", "currency"],
    "id": ["номер операции", "идентификатор", "id", "reference", "номер документа"],
}


def decode(content: bytes) -> str:
    for encoding in ("utf-8-sig", "cp1251", "latin-1"):
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise ImportError_("Не удалось определить кодировку файла")


def parse_date(value: str) -> datetime:
    """Распознает дату операции в форматах банковских выписок."""
    value = re.sub(r"\[.*?\]", "", value.strip().strip("'\"")).strip()
    compact = re.match(r"^(\d{14}|\d{8})(?!\d)", value)  # OFX: ГГГГММДД[ЧЧММСС[.мс]]
    if compact:
        digits = compact.group(1)
        fmt = "%Y%m%d%H%M%S" if len(digits) == 14 else "%Y%m%d"
        return datetime.strptime(digits, fmt).replace(tzinfo=UTC)
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(value, fmt).replace(tzinfo=UTC)
        except ValueError:
            continue
    raise ImportError_(f"Не удалось распознать дату: {value!r}")


def parse_amount(value: str) -> Decimal:
    cleaned = re.sub(r"[^\d,.\-+]", "", value.replace("\u2212", "-").replace("\xa0", ""))
    if "," in cleaned and "." in cleaned:
        # Последний из разделителей – десятичный, остальные – разделители разрядов
        if cleaned.rfind(",") > cleaned.rfind("."):
            cleaned = cleaned.replace(".", "").replace(",", ".")
        else:
            cleaned = cleaned.replace(",", "")
    else:
        cleaned = cleaned.replace(",", ".")
    try:
        return Decimal(cleaned).quantize(Decimal("0.01"))
    except InvalidOperation as exc:
        raise ImportError_(f"Не удалось распознать сумму: {value!r}") from exc


def _match_columns(header: list[str]) -> dict[str, int]:
    """Автоматическое распознавание полей выписки по названиям столбцов."""
    normalized = [h.strip().lower().replace("ё", "е") for h in header]
    found: dict[str, int] = {}
    for field, names in _COLUMNS.items():
        for name in names:
            exact = [i for i, h in enumerate(normalized) if h == name]
            partial = [i for i, h in enumerate(normalized) if name in h]
            index = (exact or partial or [None])[0]
            if index is not None and index not in found.values():
                found[field] = index
                break
    return found


def parse_csv(content: bytes) -> list[ParsedOperation]:
    text = decode(content)
    sample = text[:4096]
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=";,\t|")
    except csv.Error:
        dialect = csv.excel
        dialect.delimiter = ";" if sample.count(";") > sample.count(",") else ","
    rows = [r for r in csv.reader(io.StringIO(text), dialect) if any(c.strip() for c in r)]
    if len(rows) < 2:
        raise ImportError_("Файл не содержит операций")
    columns = _match_columns(rows[0])
    if "date" not in columns or not ({"amount", "debit", "credit"} & set(columns)):
        raise ImportError_("В файле не найдены столбцы даты и суммы")
    operations = []
    for row in rows[1:]:
        def cell(field: str, row=row) -> str:
            index = columns.get(field)
            return row[index].strip() if index is not None and index < len(row) else ""

        if not cell("date"):
            continue
        if cell("amount"):
            amount = parse_amount(cell("amount"))
        else:
            debit = parse_amount(cell("debit")) if cell("debit") else Decimal("0")
            credit = parse_amount(cell("credit")) if cell("credit") else Decimal("0")
            amount = credit - abs(debit)
        if amount == 0:
            continue
        operations.append(ParsedOperation(
            occurred_at=parse_date(cell("date")), amount=amount,
            description=cell("description"), currency=(cell("currency").upper()[:3] or None),
            external_id=cell("id"),
        ))
    return operations


def parse_ofx(content: bytes) -> list[ParsedOperation]:
    text = decode(content)
    currency_match = re.search(r"<CURDEF>\s*([A-Za-z]{3})", text)
    currency = currency_match.group(1).upper() if currency_match else None

    def tag(block: str, name: str) -> str:
        match = re.search(rf"<{name}>\s*([^<\r\n]*)", block, re.IGNORECASE)
        return match.group(1).strip() if match else ""

    operations = []
    for block in re.findall(r"<STMTTRN>(.*?)(?:</STMTTRN>|(?=<STMTTRN>)|\Z)", text,
                            re.IGNORECASE | re.DOTALL):
        if not tag(block, "DTPOSTED") or not tag(block, "TRNAMT"):
            continue
        description = " ".join(p for p in (tag(block, "NAME"), tag(block, "MEMO")) if p)
        operations.append(ParsedOperation(
            occurred_at=parse_date(tag(block, "DTPOSTED")), amount=parse_amount(tag(block, "TRNAMT")),
            description=description, currency=currency, external_id=tag(block, "FITID"),
        ))
    if not operations:
        raise ImportError_("В файле OFX не найдены операции")
    return operations


def parse_qif(content: bytes) -> list[ParsedOperation]:
    text = decode(content)
    operations, record = [], {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("!"):
            continue
        if line == "^":
            if "D" in record and "T" in record:
                description = " ".join(p for p in (record.get("P", ""), record.get("M", "")) if p)
                operations.append(ParsedOperation(
                    occurred_at=parse_date(record["D"].replace("'", "/")),
                    amount=parse_amount(record["T"]), description=description,
                    external_id=record.get("N", ""),
                ))
            record = {}
            continue
        record[line[0]] = line[1:].strip()
    if not operations:
        raise ImportError_("В файле QIF не найдены операции")
    return operations


def parse_statement(filename: str, content: bytes) -> list[ParsedOperation]:
    """Определяет формат выписки по расширению и содержимому и разбирает ее."""
    name = filename.lower()
    head = content[:2048].decode("latin-1", errors="ignore").upper()
    if name.endswith(".ofx") or "<OFX>" in head or "OFXHEADER" in head:
        return parse_ofx(content)
    if name.endswith(".qif") or head.lstrip().startswith("!TYPE"):
        return parse_qif(content)
    if name.endswith((".csv", ".txt")) or "," in head or ";" in head:
        return parse_csv(content)
    raise ImportError_("Неподдерживаемый формат файла. Допустимые форматы: CSV, OFX, QIF")
