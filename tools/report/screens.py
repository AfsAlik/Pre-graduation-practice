"""Составные рисунки из снимков экранов мобильного приложения для руководства пользователя.

Запуск:  python screens.py
Исходные снимки: mobile/screenshots (формируются тестом ScreenshotTest).
Результат: assets/screens/*.png – несколько экранов рядом, каждый в тонкой серой рамке,
под экраном – буква (а, б, в), на которую ссылается подпись рисунка.
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SOURCE = os.path.join(ROOT, "mobile", "screenshots")
TARGET = os.path.join(HERE, "assets", "screens")

SCREEN_WIDTH = 720      # ширина одного экрана в составном рисунке, пикселей
STANDARD_HEIGHT = 2673  # высота снимка экрана телефона; более длинные снимки обрезаются до нее
GAP = 48                # промежуток между экранами
LABEL_HEIGHT = 64       # строка с буквой под экраном
BORDER = "#9E9E9E"
LETTERS = "абвгд"
FONT = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"

# Имя составного рисунка -> снимки экранов слева направо
FIGURES = {
    "start": ["00_onboarding", "01_login", "01_register"],
    "dashboard": ["02_dashboard", "02_dashboard_dark", "02_dashboard_offline"],
    "add_transaction": ["03_add_transaction", "03_add_transaction_errors"],
    "transactions": ["04_transactions", "04_transactions_filters"],
    "references": ["04_accounts", "04_recurring"],
    "budget_goals": ["05_budget", "06_goals"],
    "investments": ["07_investments", "08_asset"],
    "reports": ["09_reports", "10_recommendations"],
    "profile": ["11_profile", "11_notification_settings"],
    "admin": ["12_admin_menu", "14_admin_users", "17_admin_settings"],
    "admin_logs": ["15_admin_logs", "16_admin_monitoring"],
    "admin_ml": ["12_admin_ai", "13_admin_models"],
}


def screen(name: str) -> Image.Image:
    """Снимок экрана, обрезанный до высоты экрана телефона и уменьшенный, в серой рамке."""
    with Image.open(os.path.join(SOURCE, name + ".png")) as image:
        image = image.convert("RGB")
        if image.height > STANDARD_HEIGHT:
            image = image.crop((0, 0, image.width, STANDARD_HEIGHT))
        height = round(image.height * SCREEN_WIDTH / image.width)
        image = image.resize((SCREEN_WIDTH, height), Image.LANCZOS)
    ImageDraw.Draw(image).rectangle((0, 0, image.width - 1, image.height - 1), outline=BORDER, width=3)
    return image


def compose(names: list[str]) -> Image.Image:
    screens = [screen(name) for name in names]
    height = max(s.height for s in screens)
    width = len(screens) * SCREEN_WIDTH + (len(screens) - 1) * GAP
    result = Image.new("RGB", (width, height + LABEL_HEIGHT), "white")
    draw = ImageDraw.Draw(result)
    font = ImageFont.truetype(FONT, 44)
    for index, image in enumerate(screens):
        x = index * (SCREEN_WIDTH + GAP)
        result.paste(image, (x, 0))
        if len(screens) > 1:
            draw.text((x + SCREEN_WIDTH / 2, height + LABEL_HEIGHT / 2), LETTERS[index], fill="black",
                      font=font, anchor="mm")
    return result


def main() -> None:
    os.makedirs(TARGET, exist_ok=True)
    for name, names in FIGURES.items():
        path = os.path.join(TARGET, name + ".png")
        compose(names).save(path, optimize=True)
        print(path)


if __name__ == "__main__":
    main()
