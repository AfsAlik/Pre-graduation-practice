"""Приложение Б: протокол предварительных комплексных испытаний."""
from __future__ import annotations

from docx_kit import Report


def add_protocol(report: Report, text_of) -> None:
    report.paragraph("Протокол будет сформирован после проведения испытаний.")
