"""Разбор текста чека и голосовой фразы для быстрого ввода транзакции.

Распознавание текста на фотографии чека и распознавание речи выполняются на
мобильном устройстве (ML Kit, средства Android). Полученный текст передается
сюда для выделения суммы, даты, торговой точки и определения категории.
"""
import re
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation

_TOTAL = re.compile(
    r"(?:итог[оa]?|итог к оплате|к оплате|всего|сумма|total|безналичными|оплата)\s*[:=]?\s*"
    r"(?:руб\.?|₽|rub)?\s*(\d[\d\s]*[.,]\d{2}|\d[\d\s]*)", re.IGNORECASE)
_MONEY = re.compile(r"(?<![\d.,])(\d{1,3}(?:[ \u00a0]\d{3})*[.,]\d{2}|\d+[.,]\d{2})(?![\d.,])")
_DATE = re.compile(r"(\d{2})[./-](\d{2})[./-](\d{2,4})(?:\s+(\d{2}):(\d{2}))?")
_QR = re.compile(r"t=(\d{8}T\d{4,6}).*?s=(\d+(?:\.\d{1,2})?)", re.IGNORECASE)
_SERVICE = re.compile(
    r"кассовый чек|чек №|приход|смена|кассир|инн|ккт|фн|фд|фп|ндс|сайт фнс|спасибо|"
    r"добро пожаловать|адрес|www\.|http|тел\.|\d{10,}", re.IGNORECASE)

_LEGAL = re.compile(r"^(ооо|оао|зао|ао|пао|ип)\b\s*", re.IGNORECASE)

_INCOME_WORDS = ("получил", "получила", "зарплат", "доход", "пришл", "зачисл", "вернули",
                 "аванс", "преми", "заработал", "кэшбэк", "кешбэк", "дивиденд")
_UNITS = {
    "ноль": 0, "один": 1, "одна": 1, "одну": 1, "два": 2, "две": 2, "три": 3, "четыре": 4,
    "пять": 5, "шесть": 6, "семь": 7, "восемь": 8, "девять": 9, "десять": 10,
    "одиннадцать": 11, "двенадцать": 12, "тринадцать": 13, "четырнадцать": 14,
    "пятнадцать": 15, "шестнадцать": 16, "семнадцать": 17, "восемнадцать": 18,
    "девятнадцать": 19, "двадцать": 20, "тридцать": 30, "сорок": 40, "пятьдесят": 50,
    "шестьдесят": 60, "семьдесят": 70, "восемьдесят": 80, "девяносто": 90, "сто": 100,
    "двести": 200, "триста": 300, "четыреста": 400, "пятьсот": 500, "шестьсот": 600,
    "семьсот": 700, "восемьсот": 800, "девятьсот": 900, "полтораста": 150,
}
_THOUSANDS = ("тысяча", "тысячи", "тысяч", "тыс", "тыщ", "тыщи", "косарь", "косаря")
_MILLIONS = ("миллион", "миллиона", "миллионов", "млн")
_CURRENCY_WORDS = re.compile(
    r"\b(рубл\w*|руб|р|доллар\w*|евро|юан\w*|копе\w*)\b\.?", re.IGNORECASE)
_FILLER = re.compile(
    r"\b(потратил\w*|купил\w*|заплатил\w*|оплатил\w*|получил\w*|на|за|в|из|по|с|мне|я|"
    r"сегодня|вчера|расход|доход)\b", re.IGNORECASE)


def _to_decimal(raw: str) -> Decimal | None:
    cleaned = raw.replace(" ", "").replace("\u00a0", "").replace(",", ".")
    try:
        return Decimal(cleaned).quantize(Decimal("0.01"))
    except InvalidOperation:
        return None


def parse_receipt(text: str) -> dict:
    """Выделяет из текста чека сумму, дату и название торговой точки."""
    result: dict = {"amount": None, "occurred_at": None, "merchant": "", "hint": ""}
    qr = _QR.search(text)
    if qr:  # строка QR-кода фискального чека: t=20261120T1530&s=1234.56&fn=...
        stamp = qr.group(1)
        fmt = "%Y%m%dT%H%M%S" if len(stamp) == 15 else "%Y%m%dT%H%M"
        result["occurred_at"] = datetime.strptime(stamp, fmt).replace(tzinfo=UTC)
        result["amount"] = _to_decimal(qr.group(2))
    if result["amount"] is None:
        totals = [_to_decimal(m.group(1)) for m in _TOTAL.finditer(text)]
        totals = [t for t in totals if t]
        if totals:
            result["amount"] = max(totals)
        else:
            amounts = [_to_decimal(m.group(1)) for m in _MONEY.finditer(text)]
            amounts = [a for a in amounts if a]
            result["amount"] = max(amounts) if amounts else None
    if result["occurred_at"] is None:
        match = _DATE.search(text)
        if match:
            day, month, year = int(match.group(1)), int(match.group(2)), int(match.group(3))
            year += 2000 if year < 100 else 0
            hour, minute = int(match.group(4) or 12), int(match.group(5) or 0)
            try:
                result["occurred_at"] = datetime(year, month, day, hour, minute, tzinfo=UTC)
            except ValueError:
                pass
    candidates: list[str] = []
    for line in text.splitlines():
        line = line.strip(" *-=\t")
        if len(line) >= 3 and not _SERVICE.search(line) and not _MONEY.search(line) \
                and re.search(r"[A-Za-zА-Яа-я]{3}", line):
            candidates.append(line.strip('"« »')[:200])
            if len(candidates) == 3:
                break
    if candidates:
        # Строка с юридическим лицом (ООО «…») менее информативна, чем название магазина
        trade_names = [c for c in candidates if not _LEGAL.match(c)]
        result["merchant"] = (trade_names or [_LEGAL.sub("", candidates[0]).strip('"« »')])[0]
    result["hint"] = " ".join(candidates)
    return result


def words_to_number(words: list[str]) -> tuple[Decimal | None, int]:
    """Преобразует числительные в число. Возвращает число и количество разобранных слов."""
    total = current = 0
    used = 0
    for word in words:
        word = word.lower()
        if word in _UNITS:
            current += _UNITS[word]
        elif word in _THOUSANDS:
            total += (current or 1) * 1000
            current = 0
        elif word in _MILLIONS:
            total += (current or 1) * 1_000_000
            current = 0
        elif word in ("полторы", "полтора"):
            current = 1.5  # «полторы тысячи»
        else:
            break
        used += 1
    total += current
    return (Decimal(str(total)).quantize(Decimal("0.01")) if used and total > 0 else None), used


def parse_voice(text: str) -> dict:
    """Разбирает фразу вида «потратил 500 рублей на кафе» или «получил зарплату 80 тысяч»."""
    lowered = text.lower().replace("ё", "е")
    kind = "income" if any(w in lowered for w in _INCOME_WORDS) else "expense"
    amount: Decimal | None = None
    rest = lowered
    match = re.search(r"(\d[\d\s]*(?:[.,]\d{1,2})?)\s*(тыс\w*|тыщ\w*|к\b|млн|миллион\w*)?", lowered)
    if match and match.group(1).strip():
        amount = _to_decimal(match.group(1).strip())
        if amount is not None and match.group(2):
            factor = 1_000_000 if match.group(2).startswith(("млн", "миллион")) else 1000
            amount *= factor
        rest = lowered[: match.start()] + " " + lowered[match.end():]
    else:
        tokens = re.findall(r"[а-яa-z]+", lowered)
        for index in range(len(tokens)):
            number, used = words_to_number(tokens[index:])
            if number is not None:
                amount = number
                rest = " ".join(tokens[:index] + tokens[index + used:])
                break
    description = _CURRENCY_WORDS.sub(" ", rest)
    description = _FILLER.sub(" ", description)
    description = re.sub(r"\s+", " ", description).strip(" .,")
    return {"kind": kind, "amount": amount, "description": description}
