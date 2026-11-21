"""Fill the teacher's templates for the pre-graduation practice accompanying documents.

Reads (never modifies) the two templates from «Указания по выполнению работы и шаблоны»
and writes filled copies to «Отчет по преддипломной практике»:

* Индивидуальное задание на преддипломную практику РИЗ-430938у Кандыба Н.Д.docx
* Отзыв руководителя практики РИЗ-430938у Кандыба Н.Д (проект).docx

Editing is done at run level so the template formatting is preserved; the yellow
highlight that marks the fields to fill is removed from the filled runs only.
Data sources: приказ № 626/05 от 10.08.2026 (row 8), QUESTIONS.md (answers 1 and 5).

Run:  /opt/fh-venv/bin/python tools/report/accompanying_docs.py
"""
from __future__ import annotations

import copy
from pathlib import Path

import docx
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

ROOT = Path(__file__).resolve().parents[2]
TPL_DIR = ROOT / "Указания по выполнению работы и шаблоны"
OUT_DIR = ROOT / "Отчет по преддипломной практике"

TPL_TASK = TPL_DIR / "Индивидуальное задание на преддипломную практику студента ЦУО.docx"
TPL_REVIEW = TPL_DIR / "Отзыв руководителя от предприятия (пример).docx"
OUT_TASK = OUT_DIR / "Индивидуальное задание на преддипломную практику РИЗ-430938у Кандыба Н.Д.docx"
OUT_REVIEW = OUT_DIR / "Отзыв руководителя практики РИЗ-430938у Кандыба Н.Д (проект).docx"

# --- data (приказ 626/05, строка 8) -------------------------------------------------
STUDENT_FULL = "Кандыба Никита Дмитриевич"
STUDENT_FULL_GEN = "Кандыбы Никиты Дмитриевича"  # родительный падеж: «на работу студента …»
STUDENT_SHORT = ("Кандыба", "Н.Д")            # «Фамилия И.О.» as in the template («Петров П.П.»)
PLACE = "ЦУО ИРИТ-РТФ УрФУ"                   # «Наименование предприятия» in the order
SUPERVISOR_SHORT = ("Чагаева", "О.Л")         # «Чагаева О.Л. Старший преподаватель»
SUPERVISOR_IO_F = "О.Л.\u00a0Чагаева"              # «И.О. Фамилия» as in the review («П.П. Петров»)

# Рабочий график: (наименование работ, срок). Inserted as sub-rows of the «Основной» stage.
MAIN_STAGE_DATES = "06.11.2026 – 22.12.2026"
FINAL_STAGE_DATES = "23.12.2026 – 11.01.2027"
MAIN_SUBSTAGES = [
    ("Изучение задания и требований к системе «Финансовый помощник», "
     "обследование предметной области", "06.11.2026 – 12.11.2026"),
    ("Постановка задачи: заинтересованные стороны, сбор и формулирование требований. "
     "Анализ поставленной задачи: варианты решения, обоснование архитектуры и выбора "
     "инструментальных средств, требования к интерфейсу пользователя", "13.11.2026 – 22.11.2026"),
    ("Проектирование системы: архитектура (ArchiMate, SysML), структура программного "
     "обеспечения (UML), база данных", "23.11.2026 – 29.11.2026"),
    ("Разработка серверной части и мобильного приложения системы", "30.11.2026 – 13.12.2026"),
    ("Предварительные комплексные испытания системы по программе и методикам испытаний, "
     "оформление протокола испытаний", "14.12.2026 – 18.12.2026"),
    ("Разработка руководств администратора и пользователя", "19.12.2026 – 22.12.2026"),
]


# --- helpers --------------------------------------------------------------------------
def unhighlight(run) -> None:
    run.font.highlight_color = None


def set_runs(par, first: int, last: int, text: str, *, keep_highlight=False) -> None:
    """Put `text` into run `first`, empty runs first+1..last (inclusive), drop highlight."""
    runs = par.runs
    runs[first].text = text
    for r in runs[first + 1:last + 1]:
        r._r.getparent().remove(r._r)
    if not keep_highlight:
        unhighlight(par.runs[first])


def find_run(par, text: str, start: int = 0) -> int:
    for i, r in enumerate(par.runs[start:], start):
        if r.text == text:
            return i
    raise ValueError(f"run {text!r} not found in {par.text!r}")


def unhighlight_all(par) -> None:
    for r in par.runs:
        unhighlight(r)


def set_cell_text(cell, text: str) -> None:
    p = cell.paragraphs[0]
    if p.runs:
        set_runs(p, 0, len(p.runs) - 1, text)
    else:
        p.add_run(text)
    for extra in cell.paragraphs[1:]:
        extra._p.getparent().remove(extra._p)


def set_vmerge(cell, val: str | None) -> None:
    tcPr = cell._tc.get_or_add_tcPr()
    for old in tcPr.findall(qn("w:vMerge")):
        tcPr.remove(old)
    vm = OxmlElement("w:vMerge")
    if val:
        vm.set(qn("w:val"), val)
    # schema order: tcW, gridSpan, vMerge, ... -> insert after tcW/gridSpan
    anchor = None
    for tag in ("w:gridSpan", "w:tcW"):
        anchor = tcPr.find(qn(tag))
        if anchor is not None:
            break
    if anchor is not None:
        anchor.addnext(vm)
    else:
        tcPr.insert(0, vm)


def shorten_underscores(par, idx: int, by: int) -> None:
    r = par.runs[idx]
    assert set(r.text) == {"_"}
    r.text = r.text[: max(4, len(r.text) - by)]


# --- individual assignment ------------------------------------------------------------
def fill_task() -> None:
    d = docx.Document(TPL_TASK)
    P = d.paragraphs

    # Группа (already the student's group; just drop the "fill me" highlight)
    assert "РИЗ-430938у" in P[4].text
    unhighlight_all(P[4])

    # ФИО студента on the bordered line above «(фамилия, имя, отчество)»
    assert P[12].text == "(фамилия, имя, отчество)" and P[11].text == ""
    run = P[11].add_run(STUDENT_FULL)
    mark_rpr = P[11]._p.pPr.find(qn("w:rPr"))
    if mark_rpr is not None:
        run._r.insert(0, copy.deepcopy(mark_rpr))

    # Сроки (dates in the template already match the order; drop highlight)
    assert "02.11.2026" in P[15].text and "27.12.2026" in P[15].text and "11.01.2027" in P[15].text
    unhighlight_all(P[15])

    # Место прохождения практики
    p = P[16]
    i0 = find_run(p, "_____")
    i1 = len(p.runs) - 1
    set_runs(p, i0, i1, PLACE)
    p.runs[i0].font.underline = True

    # Рабочий график
    t = d.tables[1]
    assert t.cell(2, 0).text.strip() == "Основной"
    set_cell_text(t.cell(2, 2), MAIN_STAGE_DATES)
    set_cell_text(t.cell(3, 2), FINAL_STAGE_DATES)
    for r in t.rows[1:]:
        for c in r.cells:
            for cp in c.paragraphs:
                unhighlight_all(cp)
    main_tr = t.rows[2]._tr
    set_vmerge(t.cell(2, 0), "restart")
    prev = main_tr
    for text, dates in MAIN_SUBSTAGES:
        new_tr = copy.deepcopy(main_tr)
        prev.addnext(new_tr)
        prev = new_tr
    for k, (text, dates) in enumerate(MAIN_SUBSTAGES, start=3):
        row = t.rows[k]
        c0, c1, c2, c3 = row.cells
        set_vmerge(c0, None)  # continue the «Основной» cell
        for cp in c0.paragraphs:
            for r in list(cp.runs):
                r._r.getparent().remove(r._r)
        set_cell_text(c1, text)
        set_cell_text(c2, dates)

    # Подписи
    p = P[40]
    i = find_run(p, "Иванов")
    p.runs[i].text = SUPERVISOR_SHORT[0]
    p.runs[i + 1].text = " "
    p.runs[i + 2].text = SUPERVISOR_SHORT[1]
    for r in p.runs[i:i + 4]:
        unhighlight(r)
    shorten_underscores(p, i - 1, len("Чагаева О.Л.") - len("Иванов_И.И."))

    p = P[43]
    i = find_run(p, "Петров")
    p.runs[i].text = STUDENT_SHORT[0]
    p.runs[i + 1].text = " "
    p.runs[i + 2].text = STUDENT_SHORT[1]
    for r in p.runs[i:i + 4]:
        unhighlight(r)
    shorten_underscores(p, i - 1, len("Кандыба Н.Д.") - len("Петров_П.П."))

    d.save(OUT_TASK)


# --- review ---------------------------------------------------------------------------
def fill_review() -> None:
    d = docx.Document(TPL_REVIEW)
    P = d.paragraphs

    p = P[2]
    set_runs(p, find_run(p, "Иванова Ивана Ивановича"), len(p.runs) - 1, STUDENT_FULL_GEN)

    p = P[3]
    i = find_run(p, "<вставить то, что в приказе на практику>")
    set_runs(p, i, i, PLACE)
    assert "02.11.2026" in p.text and "27.12.2026" in p.text
    unhighlight_all(p)

    assert "РИЗ-430938у" in P[5].text
    unhighlight_all(P[5])

    assert P[11].text == "12.01.2027"
    unhighlight_all(P[11])

    p = P[14]
    i = find_run(p, "<место практики>")
    set_runs(p, i, i, PLACE)
    j = find_run(p, "П.П. Петров")
    set_runs(p, j, j, SUPERVISOR_IO_F)
    # keep the signature line on one line: the place is longer than the placeholder
    shorten_underscores(p, j - 2, 8)

    d.save(OUT_REVIEW)


if __name__ == "__main__":
    OUT_DIR.mkdir(exist_ok=True)
    fill_task()
    fill_review()
    print(OUT_TASK)
    print(OUT_REVIEW)
