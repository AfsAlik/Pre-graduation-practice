"""Формирование документа Word по правилам оформления отчета.

Оформление соответствует шаблону со стилями, выданному руководителем, и
ГОСТ 7.32-2017: шрифт Times New Roman 12 пт, полуторный интервал, абзацный
отступ 1,25 см, поля 30/15/20/20 мм, нумерация страниц внизу по центру.
"""
from __future__ import annotations

import os
import re

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_LINE_SPACING, WD_TAB_ALIGNMENT, WD_TAB_LEADER
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor
from PIL import Image

FONT = "Times New Roman"
MONO = "Courier New"
TEXT_WIDTH_CM = 16.5
INDENT = Cm(1.25)
# Буквы для перечислений по ГОСТ 7.32-2017 (без ё, з, й, о, ч, ъ, ы, ь)
LETTERS = "абвгдежиклмнпрстуфхцшщэюя"


def _set_font(style_or_run, name=FONT, size=None, bold=None, italic=None):
    font = style_or_run.font
    font.name = name
    if size is not None:
        font.size = Pt(size)
    if bold is not None:
        font.bold = bold
    if italic is not None:
        font.italic = italic
    font.color.rgb = RGBColor(0, 0, 0)
    element = style_or_run.element if hasattr(style_or_run, "element") else style_or_run._element
    rpr = element.get_or_add_rPr()
    fonts = rpr.find(qn("w:rFonts"))
    if fonts is None:
        fonts = OxmlElement("w:rFonts")
        rpr.append(fonts)
    for attr in ("w:ascii", "w:hAnsi", "w:cs", "w:eastAsia"):
        fonts.set(qn(attr), name)
    for attr in ("w:asciiTheme", "w:hAnsiTheme", "w:cstheme", "w:eastAsiaTheme"):
        if fonts.get(qn(attr)) is not None:
            del fonts.attrib[qn(attr)]


def _field(run, instruction: str, cached: str = "") -> None:
    """Вставляет в прогон простое поле Word (например, PAGE)."""
    for kind, text in (("begin", None), (None, instruction), ("separate", None), ("text", cached),
                       ("end", None)):
        if kind in ("begin", "separate", "end"):
            char = OxmlElement("w:fldChar")
            char.set(qn("w:fldCharType"), kind)
            run._element.append(char)
        elif kind is None:
            instr = OxmlElement("w:instrText")
            instr.set(qn("xml:space"), "preserve")
            instr.text = text
            run._element.append(instr)
        elif text:
            t = OxmlElement("w:t")
            t.text = text
            run._element.append(t)


class Report:
    """Документ отчета и операции добавления его элементов."""

    def __init__(self) -> None:
        self.doc = Document()
        self.figures = 0
        self.tables = 0
        self.headings: list[tuple[int, str]] = []  # (уровень, текст) для содержания
        self._setup_page()
        self._setup_styles()

    # ------------------------------------------------------------ настройка
    def _setup_page(self) -> None:
        section = self.doc.sections[0]
        section.page_width, section.page_height = Cm(21), Cm(29.7)
        section.left_margin, section.right_margin = Cm(3), Cm(1.5)
        section.top_margin, section.bottom_margin = Cm(2), Cm(2)
        section.footer_distance = Cm(1)
        section.different_first_page_header_footer = True  # титульный лист без номера
        paragraph = section.footer.paragraphs[0]
        paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
        run = paragraph.add_run()
        _set_font(run, size=12)
        _field(run, "PAGE", "1")
        # Предложение обновить поля (содержание) при открытии документа в Word
        settings = self.doc.settings.element
        update = OxmlElement("w:updateFields")
        update.set(qn("w:val"), "true")
        settings.append(update)

    def _style(self, name, base="Normal", **kw):
        styles = self.doc.styles
        style = styles[name] if name in [s.name for s in styles] else styles.add_style(name, 1)
        if base and style.name != base:
            style.base_style = styles[base]
        _set_font(style, kw.get("font", FONT), kw.get("size", 12), kw.get("bold", False),
                  kw.get("italic", False))
        fmt = style.paragraph_format
        fmt.alignment = kw.get("align", WD_ALIGN_PARAGRAPH.JUSTIFY)
        fmt.first_line_indent = kw.get("first", INDENT)
        fmt.left_indent = kw.get("left", Cm(0))
        fmt.right_indent = Cm(0)
        fmt.space_before = Pt(kw.get("before", 0))
        fmt.space_after = Pt(kw.get("after", 0))
        fmt.line_spacing_rule = WD_LINE_SPACING.MULTIPLE
        fmt.line_spacing = kw.get("spacing", 1.5)
        fmt.keep_with_next = kw.get("keep_next", False)
        fmt.page_break_before = kw.get("page_break", False)
        fmt.widow_control = True
        return style

    def _setup_styles(self) -> None:
        left, center = WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER
        self._style("Normal", base=None)
        self._style("Heading 1", size=16, bold=True, align=left, keep_next=True, page_break=True)
        self._style("Heading 2", size=14, bold=True, align=left, keep_next=True, before=6)
        self._style("Heading 3", size=12, bold=True, align=left, keep_next=True, before=6)
        self._style("Structural", base="Heading 1", size=16, bold=True, align=center, first=Cm(0),
                    keep_next=True, page_break=True)
        self._style("Appendix", base="Heading 1", size=16, bold=True, align=center, first=Cm(0),
                    keep_next=True, page_break=True)
        self._style("List Letters", left=Cm(0))
        self._style("List Numbers", left=Cm(1.25))
        self._style("Table Caption", align=left, first=Cm(0), keep_next=True, before=6)
        self._style("Table Head", align=center, first=Cm(0), spacing=1.0, size=11)
        self._style("Table Text", align=left, first=Cm(0), spacing=1.0, size=11)
        self._style("Figure", align=center, first=Cm(0), keep_next=True, before=6, spacing=1.0)
        self._style("Figure Caption", align=center, first=Cm(0), after=8)
        self._style("Code", font=MONO, size=8, align=left, first=Cm(0), spacing=1.0)
        self._style("Code Body", font=MONO, size=10, align=left, first=Cm(0), spacing=1.0)
        self._style("File Heading", bold=True, align=left, keep_next=True, before=8, after=2)
        self._style("Title Center", align=center, first=Cm(0), spacing=1.0)
        self._style("Title Left", align=left, first=Cm(0), spacing=1.0)
        self._style("TOC Line", align=left, first=Cm(0))
        self._style("Source", first=INDENT)
        for name in ("Heading 1", "Heading 2", "Heading 3", "Structural", "Appendix"):
            # Заголовки не переносятся и не разрываются; уровни структуры для содержания
            self.doc.styles[name].paragraph_format.keep_together = True
        for name, level in (("Structural", 0), ("Appendix", 0)):
            ppr = self.doc.styles[name].element.get_or_add_pPr()
            outline = OxmlElement("w:outlineLvl")
            outline.set(qn("w:val"), str(level))
            ppr.append(outline)

    # ------------------------------------------------------------ текст
    def paragraph(self, text="", style="Normal", **kw):
        paragraph = self.doc.add_paragraph(style=style)
        if text:
            self.runs(paragraph, text, **kw)
        return paragraph

    @staticmethod
    def runs(paragraph, text, bold=None, italic=None, size=None, font=None):
        """Добавляет текст; фрагменты в *звездочках* выводятся курсивом."""
        for index, part in enumerate(re.split(r"\*([^*]+)\*", text)):
            if not part:
                continue
            run = paragraph.add_run(part)
            if index % 2 == 1:
                run.italic = True
            if bold is not None:
                run.bold = bold
            if italic is not None:
                run.italic = italic
            if size is not None or font is not None:
                _set_font(run, font or FONT, size)
        return paragraph

    def heading(self, text: str, level: int):
        self.headings.append((level, text))
        return self.paragraph(text, f"Heading {level}")

    def structural(self, text: str):
        """Заголовок структурного элемента: РЕФЕРАТ, ВВЕДЕНИЕ, ЗАКЛЮЧЕНИЕ и т.п."""
        self.headings.append((1, text))
        return self.paragraph(text, "Structural")

    def appendix(self, letter: str, kind: str, title: str):
        self.headings.append((1, f"ПРИЛОЖЕНИЕ {letter} ({kind}) {title}"))
        paragraph = self.doc.add_paragraph(style="Appendix")
        paragraph.add_run(f"ПРИЛОЖЕНИЕ {letter}").add_break(WD_BREAK.LINE)
        paragraph.add_run(f"({kind})").add_break(WD_BREAK.LINE)
        paragraph.add_run(title)
        return paragraph

    def letter_list(self, items: list) -> None:
        """Перечисление: элементы – строки или кортежи (строка, [вложенные строки])."""
        for index, item in enumerate(items):
            text, nested = (item, []) if isinstance(item, str) else item
            self.paragraph(f"{LETTERS[index]}) {text}", "List Letters")
            for number, sub in enumerate(nested, start=1):
                self.paragraph(f"{number}) {sub}", "List Numbers")

    def page_break(self) -> None:
        self.doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)

    # ------------------------------------------------------------ рисунки и таблицы
    def figure(self, path: str, caption: str, width_cm: float = 15.0, max_height_cm: float = 21.5) -> int:
        self.figures += 1
        with Image.open(path) as image:
            ratio = image.height / image.width
        width_cm = min(width_cm, TEXT_WIDTH_CM)
        if width_cm * ratio > max_height_cm:
            width_cm = max_height_cm / ratio
        paragraph = self.doc.add_paragraph(style="Figure")
        paragraph.add_run().add_picture(path, width=Cm(width_cm))
        self.paragraph(f"Рисунок {self.figures} – {caption}", "Figure Caption")
        return self.figures

    def table(self, caption: str | None, rows: list[list[str]], widths=None, font_size=11,
              align=None, header=True) -> int:
        """Таблица с заголовком «Таблица N – …», повторяющейся шапкой и рамкой."""
        if caption is not None:
            self.tables += 1
            self.paragraph(f"Таблица {self.tables} – {caption}", "Table Caption")
        columns = max(len(r) for r in rows)
        table = self.doc.add_table(rows=len(rows), cols=columns)
        table.style = "Table Grid"
        table.alignment = WD_TABLE_ALIGNMENT.CENTER
        table.autofit = False
        total = sum(widths) if widths else columns
        column_widths = [TEXT_WIDTH_CM * (w / total) for w in (widths or [1] * columns)]
        # Фиксированная ширина столбцов (иначе редакторы распределяют ее поровну)
        layout = OxmlElement("w:tblLayout")
        layout.set(qn("w:type"), "fixed")
        table._tbl.tblPr.append(layout)
        for column, width in zip(table.columns, column_widths, strict=True):
            column.width = Cm(width)
        for r, row in enumerate(rows):
            tr = table.rows[r]._tr
            trpr = tr.get_or_add_trPr()
            cant_split = OxmlElement("w:cantSplit")
            trpr.append(cant_split)
            if header and r == 0:
                repeat = OxmlElement("w:tblHeader")
                trpr.append(repeat)
            for c in range(columns):
                cell = table.cell(r, c)
                cell.width = Cm(column_widths[c])
                text = row[c] if c < len(row) else ""
                paragraph = cell.paragraphs[0]
                is_head = header and r == 0
                paragraph.style = self.doc.styles["Table Head" if is_head else "Table Text"]
                if not is_head and align and c < len(align):
                    paragraph.alignment = {"l": WD_ALIGN_PARAGRAPH.LEFT, "c": WD_ALIGN_PARAGRAPH.CENTER,
                                           "r": WD_ALIGN_PARAGRAPH.RIGHT}[align[c]]
                for index, line in enumerate(text.split("<br>")):
                    if index:
                        paragraph.add_run().add_break(WD_BREAK.LINE)
                    self.runs(paragraph, line.strip(), size=font_size if font_size != 11 else None)
        spacer = self.doc.add_paragraph(style="Normal")
        spacer.paragraph_format.space_after = Pt(0)
        spacer.paragraph_format.line_spacing = 0.5
        return self.tables

    # ------------------------------------------------------------ программный код
    def code(self, text: str, style="Code") -> None:
        """Листинг: моноширинный шрифт, пробелы и пустые строки сохраняются."""
        lines = text.replace("\t", "    ").rstrip("\n").split("\n")
        paragraph = self.doc.add_paragraph(style=style)
        for index, line in enumerate(lines):
            if index:
                paragraph.add_run().add_break(WD_BREAK.LINE)
            if line:
                run = paragraph.add_run(line)
                run._element.find(qn("w:t")).set(qn("xml:space"), "preserve")

    # ------------------------------------------------------------ содержание
    def toc(self, entries: list[tuple[int, str, int | str]]) -> None:
        """Содержание: поле TOC с заранее рассчитанным текстом.

        Номера страниц рассчитываются сценарием сборки; при открытии в Word поле
        можно обновить (F9), и оно будет построено заново по заголовкам.
        """
        first = True
        last_paragraph = None
        for level, text, page in entries:
            paragraph = self.doc.add_paragraph(style="TOC Line")
            fmt = paragraph.paragraph_format
            fmt.left_indent = Cm(0.5 * (level - 1))
            fmt.tab_stops.add_tab_stop(Cm(TEXT_WIDTH_CM), WD_TAB_ALIGNMENT.RIGHT, WD_TAB_LEADER.DOTS)
            if first:
                run = paragraph.add_run()
                for kind, value in (("begin", None), ("instr", 'TOC \\o "1-3" \\h \\z \\u'),
                                    ("separate", None)):
                    if kind == "instr":
                        instr = OxmlElement("w:instrText")
                        instr.set(qn("xml:space"), "preserve")
                        instr.text = value
                        run._element.append(instr)
                    else:
                        char = OxmlElement("w:fldChar")
                        char.set(qn("w:fldCharType"), kind)
                        run._element.append(char)
                first = False
            paragraph.add_run(f"{text}\t{page}")
            last_paragraph = paragraph
        if last_paragraph is not None:
            char = OxmlElement("w:fldChar")
            char.set(qn("w:fldCharType"), "end")
            last_paragraph.add_run()._element.append(char)

    def new_section(self) -> None:
        self.doc.add_section(WD_SECTION.NEW_PAGE)

    def save(self, path: str) -> None:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.doc.save(path)
