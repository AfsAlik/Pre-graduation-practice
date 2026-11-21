"""Приложение Б: протокол предварительных комплексных испытаний.

Протокол оформлен по образцу «Пример протокола предварительных комплексных
испытаний» (гриф утверждения, разделы 1–4, перечень доработок, выводы, члены
комиссии); дополнительно включен раздел «Результаты испытаний» со сводной
таблицей по этапам программы испытаний. Оценки и перечень доработок берутся из
модуля results_data – тех же данных, по которым сформирован подраздел 3.6.
"""
from __future__ import annotations

from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt

import results_data as rd
from docx_kit import Report

SYSTEM_FULL = "Мобильное приложение для управления личными финансами «Финансовый помощник»"
SYSTEM_SHORT = "Финансовый помощник, Система"
CUSTOMER = "ООО «ФинТех Старт»"
DEVELOPER = "ООО «ФинСофт Лаб»"
CONTRACT = "договор № ФП-2026-01 от 17.08.2026"
APPROVER = ("Генеральный директор", CUSTOMER, "Д.С. Морозов")
PLACE = ("620002, г. Екатеринбург, ул. Мира, д. 32 (ЦУО ИРИТ-РТФ УрФУ – место прохождения "
         "практики), тестовый стенд разработчика")
# Члены комиссии: должности и фамилии из ТЗ и ПМИ курсового проекта (листы утверждения,
# согласования и «Составили»); пустая строка – разделитель групп заказчика и разработчика
COMMISSION = [
    (f"Начальник отдела информационных технологий {CUSTOMER}", "Смирнов П.А."),
    (f"Заместитель начальника отдела информационных технологий {CUSTOMER}", "Зайцев И.В."),
    (f"Ведущий аналитик данных {CUSTOMER}", "Волкова Е.В."),
    None,
    (f"Генеральный директор {DEVELOPER}", "Кузьмин А.В."),
    (f"Программист {DEVELOPER}", "Кандыба Н.Д."),
]


def _plain_table(report: Report, rows: int, widths: list[float]):
    """Таблица без рамок для грифа утверждения и подписей."""
    table = report.doc.add_table(rows=rows, cols=len(widths))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    layout = OxmlElement("w:tblLayout")
    layout.set(qn("w:type"), "fixed")
    table._tbl.tblPr.append(layout)
    for column, width in zip(table.columns, widths, strict=True):
        column.width = Cm(width)
    for row in table.rows:
        for cell, width in zip(row.cells, widths, strict=True):
            cell.width = Cm(width)
    return table


def _cell_lines(report: Report, cell, lines: list[str], align=WD_ALIGN_PARAGRAPH.LEFT,
                bold_first: bool = False) -> None:
    for index, line in enumerate(lines):
        paragraph = cell.paragraphs[0] if index == 0 else cell.add_paragraph()
        paragraph.style = report.doc.styles["Title Left"]
        paragraph.alignment = align
        if line:
            report.runs(paragraph, line, bold=bold_first and index == 0)


def _section(report: Report, number: int, title: str) -> None:
    paragraph = report.paragraph(style="Normal")
    report.runs(paragraph, f"{number}. {title}", bold=True)
    paragraph.paragraph_format.keep_with_next = True
    paragraph.paragraph_format.space_before = Pt(6)


def add_protocol(report: Report, text_of) -> None:
    results = rd.load(strict=False)
    stages = results.stages
    totals = results.totals()

    if results.is_sample:
        report.paragraph("*Внимание: протокол сформирован по неполным результатам (файлы "
                         f"{', '.join(results.samples + results.missing)} содержат пробные данные "
                         "или отсутствуют) и подлежит повторному формированию.*")

    # Гриф утверждения – в правой половине листа, как в образце
    table = _plain_table(report, 1, [8.0, 8.5])
    position, company, name = APPROVER
    _cell_lines(report, table.cell(0, 1), ["УТВЕРЖДАЮ", "", position, company, "", "",
                                           f"_______________ {name}", "",
                                           "«____» _______________ 2026 г."])
    report.paragraph()
    for line in ("Протокол", "предварительных комплексных испытаний"):
        paragraph = report.paragraph(style="Title Center")
        report.runs(paragraph, line, bold=True, size=14)
    report.paragraph()

    _section(report, 1, "Объект испытаний")
    report.paragraph(f"Полное наименование системы – {SYSTEM_FULL}.")
    report.paragraph(f"Сокращенное наименование системы – {SYSTEM_SHORT}.")
    report.paragraph(f"Заказчик – {CUSTOMER}, разработчик – {DEVELOPER}; основание – {CONTRACT}.")

    _section(report, 2, "Сроки проведения испытаний")
    period = results.period
    if period:
        report.paragraph("Предварительные комплексные испытания проводились в период с "
                         f"{rd.long_date(period[0])} по {rd.long_date(period[1])}")
        if period[0].year == 2026:
            report.paragraph("Календарным планом технического задания испытания предусмотрены с "
                             "19.02.2027 по 26.02.2027; фактически они проведены ранее этого срока.")
    else:
        report.paragraph("Сроки проведения испытаний по файлам результатов не определены.")

    _section(report, 3, "Место проведения испытаний")
    report.paragraph(f"Предварительные комплексные испытания проводились по адресу: {PLACE}.")

    _section(report, 4, "Состав объекта испытаний")
    report.paragraph("Для проведения предварительных комплексных испытаний представлены:")
    report.letter_list([
        "техническое задание на создание мобильного приложения для управления личными финансами "
        "«Финансовый помощник»;",
        "программа и методика предварительных комплексных испытаний Системы;",
        "исходные тексты серверной части и мобильного приложения в репозитории системы контроля "
        "версий Git (приложение А настоящего отчета);",
        "сборка мобильного приложения (APK, отладочная);",
        "спецификация программного интерфейса серверной части в формате OpenAPI;",
        "руководства администратора и пользователя (раздел 4 настоящего отчета);",
        "тестовые данные: демонстрационные учетные записи с ролями «Пользователь», «Аналитик», "
        "«Администратор» и учетные записи для нагрузочного тестирования.",
    ])

    _section(report, 5, "Результаты испытаний")
    report.paragraph(
        "Испытания проведены по программе и методике предварительных комплексных испытаний. "
        "Оценка по этапам программы испытаний приведена в таблице ниже. Из "
        f"{totals['all']} пунктов методик полностью соответствуют требованиям {totals[rd.FULL]}, "
        f"частично – {totals[rd.PARTIAL]}, не соответствуют – {totals[rd.FAIL]}, не проводились – "
        f"{totals[rd.NOT_DONE]}. Результаты по каждому пункту методик, измеренные значения и "
        "способы проверки приведены в подразделе 3.6 настоящего отчета.")
    rows = [["Этап", "Проверяемый параметр", "Требования (п. ТЗ)", "Методика (п. ПМИ)", "Результат"]]
    for stage in stages:
        rows.append([str(stage.number), stage.title, stage.tz, stage.pmi, stage.verdict_text])
    report.table(None, rows, [1.2, 6.2, 3.1, 2.6, 3.4], 12, ["c", "l", "c", "c", "l"])

    _section(report, 6, "Перечень необходимых доработок и рекомендуемые сроки их выполнения")
    report.paragraph("В ходе проведения предварительных комплексных испытаний Системы выявлены "
                     "следующие необходимые доработки.")
    rows = [["№ п/п", "Описание доработки", "Рекомендуемый срок выполнения"]]
    for number, improvement in enumerate(results.improvements, start=1):
        rows.append([f"{number}.", improvement.text, improvement.deadline])
    report.table(None, rows, [1.5, 10.7, 4.3], 12, ["c", "l", "l"])

    _section(report, 7, "Выводы")
    for text in conclusions(results):
        report.paragraph(text)

    report.paragraph()
    table = _plain_table(report, len(COMMISSION) + 1, [9.0, 3.8, 3.7])
    _cell_lines(report, table.cell(0, 0), ["ЧЛЕНЫ КОМИССИИ", ""], bold_first=True)
    for index, member in enumerate(COMMISSION, start=1):
        if member is None:
            continue
        position, name = member
        _cell_lines(report, table.cell(index, 0), [position])
        _cell_lines(report, table.cell(index, 1), ["______________"], WD_ALIGN_PARAGRAPH.CENTER)
        _cell_lines(report, table.cell(index, 2), [name])
        for column in range(3):
            cell = table.cell(index, column)
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.BOTTOM
            cell.paragraphs[0].paragraph_format.space_before = Pt(14)
    del text_of


def conclusions(results: rd.Results) -> list[str]:
    """Текст раздела «Выводы» по оценкам этапов."""
    stage = results.stage
    functional = [stage(n) for n in range(5, 13)]
    failed = [s for s in results.stages if s.verdict == rd.FAIL]
    not_done = [s for s in results.stages if s.verdict == rd.NOT_DONE]
    texts = []
    if all(s.verdict != rd.FAIL for s in functional):
        texts.append(
            "В ходе предварительных комплексных испытаний проверено выполнение функций всех восьми "
            "подсистем Системы через программный интерфейс серверной части по сценариям программы "
            "и методики испытаний, в том числе реакция Системы на некорректные действия "
            "пользователей (регистрация без согласия на обработку персональных данных, неверный "
            "пароль, обращение к административным разделам без прав доступа, изменение "
            "зафиксированной позиции и достигнутой цели) и на недоступность внешнего источника "
            "данных. Функции выполняются в соответствии с техническим заданием; точность "
            "автоматической категоризации соответствует требованию ТЗ; восстановление базы данных "
            "из резервной копии выполняется без потери данных.")
    else:
        texts.append("В ходе предварительных комплексных испытаний выявлены несоответствия функций "
                     "подсистем требованиям технического задания (этапы "
                     f"{', '.join(str(s.number) for s in functional if s.verdict == rd.FAIL)}).")
    gaps = ("Не проводились проверки на мобильных устройствах и эмуляторах (эргономика, "
            "доступность, совместимость, временные характеристики операций на устройстве, работа "
            "в автономном режиме), проверки защищенного обмена данными по протоколу HTTPS, "
            "доставки push-уведомлений и писем, переключения на резервный узел базы данных")
    if any(s.number in (2, 3) for s in failed):
        gaps += "; номенклатура и оформление документации не соответствуют п. 6.3 и 9.2 ТЗ"
    texts.append(gaps + ".")
    if not_done:
        texts.append("Этапы " + ", ".join(str(s.number) for s in not_done)
                     + " программы испытаний не проводились; по остальным этапам требования ТЗ "
                       "подтверждены полностью или частично (раздел 5 настоящего протокола).")
    texts.append("Приемка Системы в опытную эксплуатацию возможна после выполнения доработок, "
                 "перечисленных в разделе 6 настоящего протокола, и проведения непроведенных "
                 "проверок с положительным результатом.")
    return texts
