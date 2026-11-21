"""Сборка отчета по преддипломной практике из файлов содержимого.

Запуск:  python build_report.py [--no-pdf]
Результат: «Отчет по преддипломной практике/Отчет … .docx» и его копия в формате PDF.

Содержимое разделов хранится в каталоге content в простом текстовом формате:
    # 1 Заголовок раздела          ## 1.1 Подраздел          ### 1.1.1 Пункт
    !struct ВВЕДЕНИЕ                – заголовок структурного элемента
    - элемент перечисления;         (два пробела и «- » – вложенный элемент)
    !table ключ | Название          – таблица, далее строки «| … | … |»
    !figure ключ | файл | Подпись | ширина, см
    {fig:ключ}, {tab:ключ}          – ссылки на рисунок и таблицу
    [@ключ]                         – ссылка на источник; !source ключ | описание
Сборка выполняется в два прохода: после первого по PDF определяются номера
страниц заголовков для содержания и общее число страниц для реферата.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.shared import Cm, Pt

import listing
import protocol
from docx_kit import Report

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CONTENT = os.path.join(HERE, "content")
OUT_DIR = os.path.join(ROOT, "Отчет по преддипломной практике")
OUT_NAME = "Отчет по преддипломной практике РИЗ-430938у Кандыба Н.Д"
FILES = ["front.md", "ch1_postanovka.md", "ch2_analysis.md", "ch3_system.md", "ch4_manuals.md",
         "back.md"]

STUDENT = "Н. Д. Кандыба"
GROUP = "РИЗ-430938у"
SUPERVISOR = "О. Л. Чагаева"
THEME = "МОБИЛЬНОЕ ПРИЛОЖЕНИЕ «ФИНАНСОВЫЙ ПОМОЩНИК»"
PRACTICE_THEME = "Разработка и предварительные комплексные испытания системы"
UDC = "004.9:336"
DOC_CODE = "09.03.03  58.29.32  008 ПЗ"
CITY_YEAR = "Екатеринбург 2027"


def load_lines() -> list[str]:
    """Читает файлы содержимого, раскрывая директивы !include."""
    lines: list[str] = []

    def read(name: str) -> None:
        with open(os.path.join(CONTENT, name), encoding="utf-8") as source:
            for line in source.read().split("\n"):
                if line.startswith("!include "):
                    read(line.split(" ", 1)[1].strip())
                else:
                    lines.append(line)
        lines.append("")

    for name in FILES:
        read(name)
    return lines


class Numbering:
    """Номера рисунков, таблиц и источников, назначаемые до формирования текста."""

    def __init__(self, lines: list[str]):
        self.figures: dict[str, int] = {}
        self.tables: dict[str, int] = {}
        self.source_text: dict[str, str] = {}
        self.sources: list[str] = []  # ключи в порядке первого упоминания
        for line in lines:
            if line.startswith("!figure "):
                self.figures[line[8:].split("|")[0].strip()] = len(self.figures) + 1
            elif line.startswith("!table "):
                self.tables[line[7:].split("|")[0].strip()] = len(self.tables) + 1
            elif line.startswith("!source "):
                key, text = line[8:].split("|", 1)
                self.source_text[key.strip()] = text.strip()
        for line in lines:
            if line.startswith("!source "):
                continue
            for group in re.findall(r"\[@([^\]]+)\]", line):
                for key in re.split(r"[,;]\s*@?", group):
                    key = key.strip().lstrip("@")
                    if key not in self.sources:
                        self.sources.append(key)
        missing = [k for k in self.sources if k not in self.source_text]
        if missing:
            raise SystemExit(f"Нет описания источников: {missing}")

    def resolve(self, text: str, variables: dict[str, str]) -> str:
        def cite(match):
            keys = [k.strip().lstrip("@") for k in re.split(r"[,;]", match.group(1))]
            return "[" + ", ".join(str(self.sources.index(k) + 1) for k in keys) + "]"

        text = re.sub(r"\[@([^\]]+)\]", cite, text)
        text = re.sub(r"\{fig:([\w\-]+)\}", lambda m: str(self.figures[m.group(1)]), text)
        text = re.sub(r"\{tab:([\w\-]+)\}", lambda m: str(self.tables[m.group(1)]), text)
        return re.sub(r"\{(\w+)\}", lambda m: variables.get(m.group(1), m.group(0)), text)


def title_page(report: Report) -> None:
    """Титульный лист по образцу «Титул отчета по преддипломной практике»."""
    doc = report.doc

    def line(text="", style="Title Center", bold=False, size=12, after=0):
        paragraph = doc.add_paragraph(style=style)
        if text:
            report.runs(paragraph, text, bold=bold, size=size)
        paragraph.paragraph_format.space_after = Pt(after)
        return paragraph

    line("Министерство науки и высшего образования Российской Федерации")
    line("ФГАОУ ВО «УрФУ имени первого Президента России Б.Н. Ельцина»")
    line("Институт радиоэлектроники и информационных технологий – РТФ")
    line("Центр ускоренного обучения", after=48)
    line(f"УДК {UDC}", style="Title Left", after=6)
    for text in ("Оценка работы______________", "", "Руководитель от УрФУ", "",
                 f"_______________ {SUPERVISOR}"):
        paragraph = line(text, style="Title Left")
        paragraph.paragraph_format.left_indent = Cm(9.5)
    for _ in range(4):
        line()
    line("ОТЧЕТ", bold=True, size=14)
    line("по преддипломной практике", size=14, after=6)
    line(THEME, bold=True, size=14, after=12)
    line(f"по теме: {PRACTICE_THEME}", after=12)
    line(DOC_CODE, after=60)

    def signature(left: str, name: str, notes: tuple[str, str]) -> None:
        paragraph = line(style="Title Left")
        paragraph.paragraph_format.tab_stops.add_tab_stop(Cm(9.2))
        paragraph.paragraph_format.tab_stops.add_tab_stop(Cm(13.2))
        report.runs(paragraph, f"{left}\t__________________\t{name}")
        note = line(style="Title Left", after=10)
        note.paragraph_format.tab_stops.add_tab_stop(Cm(4.4))
        note.paragraph_format.tab_stops.add_tab_stop(Cm(10.4))
        report.runs(note, f"\t{notes[0]}\t{notes[1]}", size=9)

    signature("Руководитель практики от предприятия", SUPERVISOR, ("", "подпись"))
    signature(f"Студент группы {GROUP}", STUDENT, ("номер группы", "подпись"))
    for _ in range(5):
        line()
    line(CITY_YEAR)
    doc.paragraphs[-1].add_run().add_break(WD_BREAK.PAGE)


def parse_row(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def build(pages: dict[str, int], total_pages: int) -> tuple[Report, Numbering]:
    lines = load_lines()
    numbering = Numbering(lines)
    report = Report()
    variables = {
        "pages": str(total_pages), "figures": str(len(numbering.figures)),
        "tables": str(len(numbering.tables)), "sources": str(len(numbering.sources)),
        "appendices": "2",
    }

    def text_of(value: str) -> str:
        return numbering.resolve(value, variables)

    index = 0
    list_items: list = []

    def flush_list() -> None:
        if list_items:
            report.letter_list(list_items)
            list_items.clear()

    while index < len(lines):
        line = lines[index]
        index += 1
        if line.startswith("  - "):
            if list_items:
                last = list_items[-1]
                if isinstance(last, str):
                    list_items[-1] = (last, [text_of(line[4:])])
                else:
                    last[1].append(text_of(line[4:]))
            continue
        if line.startswith("- "):
            list_items.append(text_of(line[2:]))
            continue
        flush_list()
        if not line.strip():
            continue
        if line.startswith("### "):
            report.heading(text_of(line[4:]), 3)
        elif line.startswith("## "):
            report.heading(text_of(line[3:]), 2)
        elif line.startswith("# "):
            report.heading(text_of(line[2:]), 1)
        elif line.startswith("!struct "):
            report.structural(line[8:].strip())
        elif line == "!title":
            title_page(report)
        elif line == "!toc":
            entries = [(level, text, pages.get(text, 0)) for level, text in ALL_HEADINGS]
            report.toc(entries)
        elif line.startswith("!term "):
            term, definition = line[6:].split("|", 1)
            paragraph = report.paragraph()
            paragraph.add_run(term.strip()).italic = True
            report.runs(paragraph, f" – {text_of(definition.strip())}")
        elif line.startswith("!table "):
            key, caption = (part.strip() for part in line[7:].split("|", 1))
            options = {"widths": None, "font": 11, "align": None}
            while index < len(lines) and lines[index].startswith(("!widths", "!font", "!align")):
                name, value = lines[index][1:].split(" ", 1)
                options[name] = ([float(v) for v in value.split(",")] if name == "widths" else
                                 int(value) if name == "font" else value.replace(" ", "").split(","))
                index += 1
            rows = []
            while index < len(lines) and lines[index].lstrip().startswith("|"):
                rows.append([text_of(cell) for cell in parse_row(lines[index])])
                index += 1
            report.table(text_of(caption), rows, options["widths"], options["font"], options["align"])
            del key
        elif line.startswith("!figure "):
            _key, path, caption, *rest = (part.strip() for part in line[8:].split("|"))
            report.figure(os.path.join(HERE, path), text_of(caption), float(rest[0]) if rest else 15.0)
        elif line == "!code":
            block = []
            while index < len(lines) and lines[index] != "!endcode":
                block.append(lines[index])
                index += 1
            index += 1
            report.code("\n".join(block), "Code Body")
        elif line.startswith("!center "):
            report.paragraph(text_of(line[8:]), "Title Center")
        elif line.startswith("!formula "):
            body, number = line[9:].rsplit("|", 1)
            paragraph = report.paragraph(style="Title Left")
            paragraph.paragraph_format.tab_stops.add_tab_stop(Cm(8.25), WD_ALIGN_PARAGRAPH.CENTER)
            paragraph.paragraph_format.tab_stops.add_tab_stop(Cm(16.5), WD_ALIGN_PARAGRAPH.RIGHT)
            paragraph.paragraph_format.space_before = paragraph.paragraph_format.space_after = Pt(6)
            report.runs(paragraph, f"\t{body.strip()}\t({number.strip()})")
        elif line == "!pagebreak":
            report.page_break()
        elif line.startswith("!appendix "):
            letter, kind, title = (part.strip() for part in line[10:].split("|"))
            report.appendix(letter, kind, title)
        elif line == "!sources":
            for number, key in enumerate(numbering.sources, start=1):
                report.paragraph(f"{number} {numbering.source_text[key]}", "Source")
        elif line == "!listing":
            listing.add_listing(report, ROOT)
        elif line == "!protocol":
            protocol.add_protocol(report, text_of)
        elif line.startswith("!source "):
            continue
        elif line.startswith("!"):
            raise SystemExit(f"Неизвестная директива: {line}")
        else:
            report.paragraph(text_of(line))
    flush_list()
    return report, numbering


ALL_HEADINGS: list[tuple[int, str]] = []


def pdf_pages(pdf: str) -> list[str]:
    text = subprocess.run(["pdftotext", "-layout", pdf, "-"], capture_output=True, text=True,
                          check=True).stdout
    return text.split("\f")


def locate_headings(pdf: str, headings: list[tuple[int, str]]) -> tuple[dict[str, int], int]:
    """Определяет номера страниц заголовков по тексту PDF."""
    pages = pdf_pages(pdf)

    def norm(value: str) -> str:
        return re.sub(r"\s+", " ", value).strip().lower()

    normalized = [[norm(line) for line in page.split("\n")] for page in pages]
    result: dict[str, int] = {}
    current = 1  # титульный лист и содержание заголовков не содержат
    for _, text in headings:
        probe = norm(text)[:45]
        first_words = norm(text.split("(")[0])[:45]
        for page_index in range(current, len(pages)):
            if any((line.startswith(probe) or (first_words and line == first_words)
                    or (len(line) >= 20 and probe.startswith(line)))
                   and "...." not in line for line in normalized[page_index]):
                result[text] = page_index + 1
                current = page_index
                break
        else:
            print(f"  предупреждение: заголовок не найден в PDF: {text}")
    return result, len([p for p in pages if p.strip()])


def to_pdf(docx_path: str) -> str:
    subprocess.run(["soffice", "--headless", "--convert-to", "pdf", "--outdir",
                    os.path.dirname(docx_path), docx_path], check=True, capture_output=True)
    return docx_path[:-5] + ".pdf"


def main() -> None:
    global ALL_HEADINGS
    docx_path = os.path.join(OUT_DIR, OUT_NAME + ".docx")
    # Первый проход: состав заголовков; номера страниц еще неизвестны
    ALL_HEADINGS = []
    report, numbering = build({}, 0)
    ALL_HEADINGS = [h for h in report.headings if h[1] not in ("РЕФЕРАТ", "СОДЕРЖАНИЕ")]
    pages: dict[str, int] = {}
    total = 0
    for attempt in range(3):
        report, numbering = build(pages, total)
        report.save(docx_path)
        if "--no-pdf" in sys.argv:
            break
        pdf = to_pdf(docx_path)
        new_pages, new_total = locate_headings(pdf, ALL_HEADINGS)
        print(f"проход {attempt + 1}: страниц {new_total}, заголовков {len(new_pages)}")
        if new_pages == pages and new_total == total:
            break
        pages, total = new_pages, new_total
    print(f"Рисунков: {len(numbering.figures)}, таблиц: {len(numbering.tables)}, "
          f"источников: {len(numbering.sources)}")
    print(docx_path)


if __name__ == "__main__":
    main()
