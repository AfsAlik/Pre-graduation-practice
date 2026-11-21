"""Экспорт отчетов и истории операций в форматы PDF, XLSX и CSV (п. 4.2.1.7 ТЗ)."""
import csv
import io
import os
from datetime import datetime
from decimal import Decimal

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

KIND_TITLES = {"income": "Доход", "expense": "Расход", "transfer": "Перевод"}
HEADERS = ["Дата", "Вид", "Категория", "Счет", "Сумма", "Валюта", "Описание"]
_FONT_CANDIDATES = [
    os.getenv("FH_PDF_FONT", ""),
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans.ttf",
    "C:/Windows/Fonts/arial.ttf",
    "/Library/Fonts/Arial.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
]
_font_name: str | None = None


def pdf_font() -> str:
    """Шрифт с поддержкой кириллицы для PDF."""
    global _font_name
    if _font_name is None:
        _font_name = "Helvetica"
        for path in _FONT_CANDIDATES:
            if path and os.path.exists(path):
                pdfmetrics.registerFont(TTFont("FinHelperSans", path))
                _font_name = "FinHelperSans"
                break
    return _font_name


def transaction_rows(transactions, accounts: dict, categories: dict) -> list[list]:
    rows = []
    for item in transactions:
        account = accounts.get(item.account_id)
        category = categories.get(item.category_id)
        description = " ".join(p for p in (item.merchant, item.comment) if p)
        if item.kind == "transfer":
            target = accounts.get(item.to_account_id)
            description = f"Перевод на счет «{target.name if target else ''}» {description}".strip()
        sign = Decimal("1") if item.kind == "income" else Decimal("-1")
        rows.append([
            item.occurred_at.strftime("%d.%m.%Y %H:%M"), KIND_TITLES.get(item.kind, item.kind),
            category.name if category else "", account.name if account else "",
            Decimal(item.amount) * sign, item.currency, description,
        ])
    return rows


def to_csv(rows: list[list], headers: list[str] | None = None) -> bytes:
    buffer = io.StringIO()
    writer = csv.writer(buffer, delimiter=";")
    writer.writerow(headers or HEADERS)
    for row in rows:
        writer.writerow(row)
    return buffer.getvalue().encode("utf-8-sig")


def to_xlsx(rows: list[list], title: str, summary: list[tuple[str, str]] | None = None,
            headers: list[str] | None = None) -> bytes:
    headers = headers or HEADERS
    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "Операции"
    sheet.append([title])
    sheet["A1"].font = Font(bold=True, size=14)
    sheet.append([f"Сформировано: {datetime.now().strftime('%d.%m.%Y %H:%M')}"])
    for name, value in summary or []:
        sheet.append([name, value])
    sheet.append([])
    sheet.append(headers)
    header_row = sheet.max_row
    for cell in sheet[header_row]:
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor="2E7D32")
        cell.alignment = Alignment(horizontal="center")
    for row in rows:
        sheet.append(row)
    for index, header in enumerate(headers, start=1):
        width = max([len(str(header))] + [len(str(r[index - 1])) for r in rows[:500]]) + 2
        sheet.column_dimensions[get_column_letter(index)].width = min(width, 60)
        if header == "Сумма":
            for cell in sheet[get_column_letter(index)][header_row:]:
                cell.number_format = "#,##0.00"
    sheet.freeze_panes = sheet.cell(row=header_row + 1, column=1)
    buffer = io.BytesIO()
    workbook.save(buffer)
    return buffer.getvalue()


def to_pdf(rows: list[list], title: str, summary: list[tuple[str, str]] | None = None,
           headers: list[str] | None = None) -> bytes:
    headers = headers or HEADERS
    font = pdf_font()
    buffer = io.BytesIO()
    document = SimpleDocTemplate(buffer, pagesize=landscape(A4), leftMargin=12 * mm,
                                 rightMargin=12 * mm, topMargin=12 * mm, bottomMargin=12 * mm,
                                 title=title)
    title_style = ParagraphStyle("title", fontName=font, fontSize=14, leading=18)
    text_style = ParagraphStyle("text", fontName=font, fontSize=9, leading=12)
    cell_style = ParagraphStyle("cell", fontName=font, fontSize=8, leading=10)
    story = [Paragraph(title, title_style), Spacer(1, 4 * mm)]
    for name, value in summary or []:
        story.append(Paragraph(f"{name}: {value}", text_style))
    story.append(Spacer(1, 4 * mm))
    data = [headers]
    for row in rows:
        data.append([
            Paragraph(str(cell), cell_style) if isinstance(cell, str) and len(cell) > 18
            else (f"{cell:,.2f}".replace(",", " ") if isinstance(cell, Decimal) else str(cell))
            for cell in row
        ])
    widths = None
    if headers == HEADERS:
        widths = [30 * mm, 20 * mm, 40 * mm, 40 * mm, 28 * mm, 16 * mm, 95 * mm]
    table = Table(data, repeatRows=1, colWidths=widths)
    table.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), font),
        ("FONTSIZE", (0, 0), (-1, -1), 8),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#2E7D32")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("GRID", (0, 0), (-1, -1), 0.25, colors.grey),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F5F5")]),
    ]))
    story.append(table)
    document.build(story)
    return buffer.getvalue()


CONTENT_TYPES = {
    "csv": "text/csv; charset=utf-8",
    "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pdf": "application/pdf",
}


def render(fmt: str, rows: list[list], title: str, summary=None, headers=None) -> bytes:
    if fmt == "csv":
        return to_csv(rows, headers)
    if fmt == "xlsx":
        return to_xlsx(rows, title, summary, headers)
    if fmt == "pdf":
        return to_pdf(rows, title, summary, headers)
    raise ValueError(f"Неподдерживаемый формат экспорта: {fmt}")
