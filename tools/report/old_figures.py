"""Перерисовка двух рисунков из прежних работ: результаты опроса и информационная структура."""
import sys
from html import escape

sys.path.insert(0, "/home/user/Pre-graduation-practice/tools/report")
from diagram_kit import Diagram, WHITE, NEUTRAL  # noqa: E402

OUT = sys.argv[1]
FONT = "Liberation Sans, Arial, sans-serif"
INK, INK2, GRID, BLUE = "#0b0b0b", "#52514e", "#d9d8d2", "#2a78d6"
N = 67


def survey():
    W, col_w, label_w, unit = 1000, 500, 212, 2.45
    panels_left = [
        ("Наиболее важные функции (несколько ответов)", [
            ("Быстрый ввод", 56), ("Категории", 52), ("Планирование бюджета", 48),
            ("Рекомендации", 48), ("Финансовые цели", 41), ("Синхронизация с банками", 34),
            ("Учет инвестиций", 30)]),
        ("Важность автономного (офлайн) режима", [("Важен", 56), ("Не важен", 11)]),
    ]
    panels_right = [
        ("Ведение учета доходов и расходов", [("Постоянно", 30), ("Иногда", 22), ("Не ведут", 15)]),
        ("Предпочтительный способ ввода", [("Импорт из банка", 33), ("Ручной ввод", 26), ("Фото чека", 7)]),
        ("Готовность платить за премиум-функции", [("Да", 19), ("Зависит от цены", 33), ("Нет", 15)]),
    ]
    row, bar, title_h, gap = 31, 18, 34, 22
    out = []

    def column(x0, panels):
        y = 14
        for title, rows in panels:
            out.append(f'<text x="{x0}" y="{y + 18}" font-size="17" font-weight="bold" fill="{INK}">{escape(title)}</text>')
            y += title_h
            bx = x0 + label_w
            out.append(f'<line x1="{bx}" y1="{y - 3}" x2="{bx}" y2="{y + row * len(rows) - 5}" stroke="{GRID}" stroke-width="1.5"/>')
            for name, value in rows:
                cy = y + bar / 2 + 2
                w = value * unit
                out.append(f'<text x="{bx - 10}" y="{cy + 6}" font-size="16.5" text-anchor="end" fill="{INK}">{escape(name)}</text>')
                out.append(f'<path d="M{bx},{cy - bar / 2} h{w - 4} a4,4 0 0 1 4,4 v{bar - 8} a4,4 0 0 1 -4,4 h{-(w - 4)} z" fill="{BLUE}"/>')
                pct = round(value * 100 / N)
                out.append(f'<text x="{bx + w + 8}" y="{cy + 6}" font-size="16" fill="{INK}">{value} ({pct} %)</text>')
                y += row
            y += gap
        return y

    h = max(column(14, panels_left), column(14 + col_w + 6, panels_right)) - gap + 12
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{int(h)}" font-family="{FONT}">'
           f'<rect width="100%" height="100%" fill="#fff"/>' + "".join(out) + "</svg>")
    with open(f"{OUT}/survey_results.svg", "w", encoding="utf-8") as f:
        f.write(svg)


def info_structure():
    d = Diagram(1000, 706)
    cols = [
        (20, "Публичная часть", "без авторизации", [
            ("Вход", "авторизация"),
            ("Регистрация", "создание учетной записи"),
            ("Восстановление пароля", "ссылка на электронную почту")]),
        (355, "Личный кабинет", "роль «Пользователь»", [
            ("Дашборд", "баланс, бюджет, цели, портфель"),
            ("Транзакции", "список, добавление, категории"),
            ("Бюджет", "лимиты, уведомления"),
            ("Цели", "добавление, прогресс"),
            ("Инвестиции", "портфель, доходность"),
            ("Отчеты и аналитика", "графики, прогнозы"),
            ("Рекомендации", "список советов, оценка полезности"),
            ("Профиль", "настройки, валюта, риск-профиль"),
            ("Справка и поддержка", "частые вопросы, обращение")]),
        (690, "Административный модуль", "роли «Аналитик», «Администратор»", [
            ("Аналитика ИИ", "качество моделей, данные обучения"),
            ("Управление моделями", "запуск обучения, версии"),
            ("Пользователи", "список, роли, блокировка"),
            ("Журнал аудита", "просмотр действий"),
            ("Мониторинг", "доступность, производительность"),
            ("Настройки системы", "")]),
    ]
    w, head_h, item_h, step = 290, 58, 50, 58
    root = d.rect(255, 14, 490, 46, fill=NEUTRAL, rx=6)
    d.text(root.cx, root.cy + 6, "Мобильное приложение «Финансовый помощник»", 17, weight="bold")
    bus_y, head_y = 82, 104
    d.edge([root.bottom(), (root.cx, bus_y)])
    d.edge([(cols[0][0] + w / 2, bus_y), (cols[2][0] + w / 2, bus_y)])
    for x, title, sub, items in cols:
        head = d.rect(x, head_y, w, head_h, fill="#C6ECFB", rx=6)
        d.edge([(head.cx, bus_y), head.top()], kind="dir")
        d.text(head.cx, head.y + 25, title, 17, weight="bold")
        d.text(head.cx, head.y + 46, sub, 14)
        trunk_x = x + 16
        y = head_y + head_h + 14
        last = y + step * (len(items) - 1) + item_h / 2
        d.edge([(trunk_x, head_y + head_h), (trunk_x, last)])
        for name, detail in items:
            box = d.rect(x + 34, y, w - 34, item_h, fill=WHITE, rx=4)
            d.edge([(trunk_x, box.cy), box.left()])
            if detail:
                d.text(box.x + 12, box.y + 21, name, 16, anchor="start", weight="bold")
                d.text(box.x + 12, box.y + 40, detail, 12.5, anchor="start")
            else:
                d.text(box.x + 12, box.cy + 6, name, 16, anchor="start", weight="bold")
            y += step
    d.save(f"{OUT}/info_structure.svg")


survey()
info_structure()
