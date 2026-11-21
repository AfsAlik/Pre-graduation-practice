"""Приложение А: исходный текст программы.

Файлы группируются по пакетам. Заголовки пакетов попадают в содержание,
названия файлов выводятся полужирным шрифтом перед текстом файла.
"""
from __future__ import annotations

import os

from docx_kit import Report

SERVER_GROUPS = [
    ("Серверное приложение: пакет app", "server/app", False),
    ("Программный интерфейс: пакет app.routers", "server/app/routers", False),
    ("Бизнес-логика подсистем: пакет app.services", "server/app/services", False),
    ("Машинное обучение: пакет app.ml", "server/app/ml", False),
    ("Сервис машинного обучения: пакет ml_service", "server/ml_service", False),
]
# Порядок файлов внутри пакета: сначала основные модули
FIRST = ["main.py", "config.py", "db.py", "models.py", "schemas.py", "security.py", "audit.py",
         "bootstrap.py", "worker.py", "manage.py", "MainActivity.kt", "FinHelperApp.kt",
         "AppContainer.kt"]
SKIP_FILES = {"__init__.py", "control_set.py", "dataset.py"}
MOBILE_SRC = "mobile/app/src/main/java"


def _files(directory: str, extensions: tuple[str, ...]) -> list[str]:
    names = [n for n in os.listdir(directory)
             if n.endswith(extensions) and os.path.isfile(os.path.join(directory, n))]
    names.sort(key=lambda n: (FIRST.index(n) if n in FIRST else len(FIRST), n.lower()))
    return names


def _add_file(report: Report, root: str, relative: str, number: str) -> int:
    with open(os.path.join(root, relative), encoding="utf-8") as source:
        text = source.read()
    report.paragraph(f"{number} Файл {relative}", "File Heading")
    report.code(text)
    return text.count("\n") + 1


def collect_groups(root: str) -> list[tuple[str, list[str]]]:
    """Возвращает список (название пакета, файлы) в порядке вывода."""
    groups: list[tuple[str, list[str]]] = []
    for title, directory, _ in SERVER_GROUPS:
        full = os.path.join(root, directory)
        if not os.path.isdir(full):
            continue
        files = [f"{directory}/{name}" for name in _files(full, (".py",)) if name not in SKIP_FILES]
        if files:
            groups.append((title, files))
    mobile = os.path.join(root, MOBILE_SRC)
    if os.path.isdir(mobile):
        for current, _dirs, _names in sorted(os.walk(mobile)):
            names = _files(current, (".kt",))
            if not names:
                continue
            package = os.path.relpath(current, mobile).replace(os.sep, ".")
            relative_dir = os.path.relpath(current, root).replace(os.sep, "/")
            groups.append((f"Мобильное приложение: пакет {package}",
                           [f"{relative_dir}/{name}" for name in names]))
    return groups


def add_listing(report: Report, root: str) -> dict:
    """Добавляет листинги в отчет. Возвращает число файлов и строк."""
    stats = {"files": 0, "lines": 0}
    for index, (title, files) in enumerate(collect_groups(root), start=1):
        report.heading(f"А.{index} {title}", 2)
        for file_index, relative in enumerate(files, start=1):
            stats["lines"] += _add_file(report, root, relative, f"А.{index}.{file_index}")
            stats["files"] += 1
    return stats
