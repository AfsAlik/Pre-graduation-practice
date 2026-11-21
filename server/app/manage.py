"""Команды администрирования серверной части.

    python -m app.manage init                     – справочники, начальная модель, курсы валют
    python -m app.manage create-user EMAIL PASSWORD [--role admin|analyst|user] [--name ИМЯ]
    python -m app.manage seed-demo                – тестовые учетные записи и демонстрационные данные
    python -m app.manage seed-load [--users N]    – учетные записи для нагрузочного тестирования
    python -m app.manage backup [--dir КАТАЛОГ]   – резервная копия базы данных (pg_dump)
    python -m app.manage restore ФАЙЛ             – восстановление базы данных из копии
    python -m app.manage check                    – проверка работоспособности компонентов
"""
import argparse
import os
import random
import subprocess
import sys
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

from sqlalchemy.engine import make_url

from . import bootstrap
from .config import get_settings
from .db import SessionLocal
from .models import Account, Transaction
from .services import entities, monitoring, tasks
from .services.categories import find_by_name
from .services.periods import add_months, month_start

DEMO_PASSWORD = "Demo-2026-pass"
DEMO_USERS = [
    ("user@example.com", "user", "Анна Петрова"),
    ("investor@example.com", "user", "Сергей Иванов"),
    ("analyst@example.com", "analyst", "Елена Волкова"),
    ("admin@example.com", "admin", "Игорь Зайцев"),
]
MONTHLY_EXPENSES = [
    ("Жилье", "Аренда квартиры", 30000, 30000, 1),
    ("Продукты", "Пятерочка", 1800, 4200, 6),
    ("Продукты", "ВкусВилл", 900, 2500, 3),
    ("Кафе", "Шоколадница", 600, 1900, 4),
    ("Транспорт", "Яндекс Такси", 250, 700, 5),
    ("Транспорт", "Лукойл АЗС", 2000, 3200, 2),
    ("Связь", "МТС", 650, 650, 1),
    ("Развлечения", "Кинопоиск", 399, 399, 1),
    ("Развлечения", "Яндекс Плюс", 299, 299, 1),
    ("Здоровье", "Аптека Ригла", 400, 1500, 1),
    ("Дом и быт", "Ozon", 800, 4500, 2),
]


def _pg_env() -> tuple[dict, list[str]]:
    """Параметры подключения для утилит pg_dump и pg_restore."""
    url = make_url(get_settings().database_url)
    if not url.drivername.startswith("postgresql"):
        sys.exit("Резервное копирование поддерживается только для PostgreSQL")
    env = {**os.environ, "PGPASSWORD": url.password or ""}
    args = ["-h", url.host or "localhost", "-p", str(url.port or 5432), "-U", url.username or "postgres"]
    return env, [*args, "-d", url.database]


def backup(directory: str) -> Path:
    """Создает полную резервную копию базы данных в собственном формате PostgreSQL."""
    env, args = _pg_env()
    target = Path(directory)
    target.mkdir(parents=True, exist_ok=True)
    path = target / f"finhelper_{datetime.now(UTC).strftime('%Y%m%d_%H%M%S')}.dump"
    subprocess.run(["pg_dump", *args, "-Fc", "-f", str(path)], env=env, check=True)
    print(f"Резервная копия создана: {path} ({path.stat().st_size} байт)")
    return path


def remove_old_backups(directory: str, keep_days: int) -> int:
    """Удаляет резервные копии старше срока хранения. Возвращает число удаленных файлов."""
    border = datetime.now(UTC).timestamp() - keep_days * 86400
    removed = 0
    for path in Path(directory).glob("finhelper_*.dump"):
        if path.stat().st_mtime < border:
            path.unlink()
            removed += 1
    return removed


def restore(path: str) -> None:
    """Восстанавливает базу данных из копии. Существующие объекты заменяются."""
    env, args = _pg_env()
    subprocess.run(["pg_restore", *args, "--clean", "--if-exists", "--no-owner", "-1", path],
                   env=env, check=True)
    print(f"База данных восстановлена из {path}")


def fill_demo_data(db, user, rng: random.Random) -> int:
    """Заполняет учетную запись демонстрационными данными. Возвращает число операций.

    Создаются 3 счета, операции за 6 месяцев (не менее 50), бюджетные лимиты,
    2 финансовые цели, 5 инвестиционных позиций, регулярный платеж и шаблон.
    """

    def cat(name, kind="expense"):
        return find_by_name(db, user.id, name, kind).id

    def save(entity, data):
        return entities.save_entity(db, user, entity, None, data)

    card = save("account", {"name": "Зарплатная карта", "type": "bank", "initial_balance": "45000"})
    cash = save("account", {"name": "Наличные", "type": "cash", "initial_balance": "8000"})
    usd = save("account", {"name": "Валютный счет", "type": "bank", "currency": "USD",
                           "initial_balance": "500"})
    today = date.today()
    for back in range(6, -1, -1):
        first = add_months(month_start(today), -back)
        salary_day = datetime.combine(first + timedelta(days=4), datetime.min.time(), tzinfo=UTC)
        if salary_day.date() <= today:
            save("transaction", {"kind": "income", "amount": "95000", "account_id": card.id,
                                 "category_id": cat("Зарплата", "income"),
                                 "occurred_at": salary_day + timedelta(hours=9),
                                 "comment": "Зарплата"})
        for category, merchant, low, high, count in MONTHLY_EXPENSES:
            for _ in range(count):
                day = first + timedelta(days=rng.randint(0, 27))
                if day > today:
                    continue
                moment = datetime.combine(day, datetime.min.time(), tzinfo=UTC) \
                    + timedelta(hours=rng.randint(8, 21), minutes=rng.randint(0, 59))
                account = cash if merchant == "Шоколадница" and rng.random() < 0.3 else card
                save("transaction", {"kind": "expense", "amount": str(rng.randint(low, high)),
                                     "account_id": account.id, "category_id": cat(category),
                                     "merchant": merchant, "occurred_at": moment})
    save("transaction", {"kind": "transfer", "amount": "9000", "account_id": card.id,
                         "to_account_id": usd.id, "comment": "Покупка валюты"})
    for category, limit in (("Продукты", "22000"), ("Кафе", "6000"), ("Транспорт", "9000"),
                            ("Развлечения", "3000"), ("Дом и быт", "8000")):
        save("budget", {"category_id": cat(category), "period": "month", "limit_amount": limit})
    vacation = save("goal", {"name": "Отпуск", "target_amount": "150000", "priority": "high",
                             "deadline": add_months(today, 6)})
    reserve = save("goal", {"name": "Резервный фонд", "target_amount": "300000",
                            "priority": "medium", "deadline": add_months(today, 18)})
    for goal, amount in ((vacation, "20000"), (vacation, "25000"), (reserve, "40000")):
        save("goal_contribution", {"goal_id": goal.id, "amount": amount})
    for asset_type, ticker, name, quantity, price, fee in (
        ("stock", "SBER", "Сбербанк", "50", "265", "40"),
        ("stock", "LKOH", "Лукойл", "3", "6800", "60"),
        ("etf", "TMOS", "Фонд Индекс МосБиржи", "2000", "6.8", "0"),
        ("bond", "SU26238RMFS4", "ОФЗ 26238", "20", "540", "30"),
        ("currency", "USD", "Доллар США", "300", "88", "0"),
    ):
        save("investment_trade", {"asset_type": asset_type, "ticker": ticker, "name": name,
                                  "quantity": quantity, "price": price, "fee": fee,
                                  "traded_at": datetime.now(UTC) - timedelta(days=60)})
    save("recurring_payment", {"name": "Аренда квартиры", "amount": "30000",
                               "account_id": card.id, "category_id": cat("Жилье"),
                               "period": "month", "next_date": add_months(month_start(today), 1)})
    save("template", {"name": "Обед", "kind": "expense", "amount": "450",
                      "account_id": card.id, "category_id": cat("Кафе")})
    db.commit()
    return db.query(Transaction).filter(Transaction.user_id == user.id).count()


def seed_users(users: list[tuple[str, str, str]], password: str, seed: int = 2026) -> None:
    """Создает учетные записи и заполняет их демонстрационными данными.

    На время заполнения уведомления отключаются, чтобы исторические операции не
    порождали поток сообщений; после заполнения настройки возвращаются.
    """
    tasks.run_inline = True
    # Котировки обновятся при первом открытии портфеля; при загрузке данных биржа не опрашивается
    tasks.skipped.add("refresh_quote")
    rng = random.Random(seed)
    db = SessionLocal()
    try:
        for email, role, name in users:
            user = bootstrap.create_user(db, email, password, role, name)
            user.onboarding_done = True
            if db.query(Account).filter(Account.user_id == user.id).first() is not None:
                print(f"{email}: данные уже существуют")
                continue
            settings = dict(user.notification_settings or {})
            user.notification_settings = {**settings, "budget": False, "goals": False}
            db.commit()
            count = fill_demo_data(db, user, rng)
            user.notification_settings = settings
            db.commit()
            print(f"{email}: создано операций – {count}")
    finally:
        db.close()


def seed_demo() -> None:
    """Тестовые учетные записи с ролями «Пользователь», «Аналитик», «Администратор»."""
    seed_users(DEMO_USERS, DEMO_PASSWORD)
    print(f"Пароль тестовых учетных записей: {DEMO_PASSWORD}")


def seed_load(count: int) -> None:
    """Учетные записи виртуальных пользователей для нагрузочного тестирования."""
    users = [(f"load{i:03d}@example.com", "user", f"Нагрузка {i:03d}") for i in range(1, count + 1)]
    seed_users(users, DEMO_PASSWORD, seed=7)


def check() -> int:
    db = SessionLocal()
    try:
        result = monitoring.run_health_check(db)
        db.commit()
        print(f"База данных: {'да' if result.database_ok else 'НЕТ'}; сервис машинного обучения: "
              f"{'да' if result.ml_ok else 'НЕТ'}; курсы валют актуальны: "
              f"{'да' if result.rates_ok else 'НЕТ'}")
        return 0 if result.database_ok and result.ml_ok else 1
    finally:
        db.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Администрирование «Финансового помощника»")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init")
    create = commands.add_parser("create-user")
    create.add_argument("email")
    create.add_argument("password")
    create.add_argument("--role", default="user", choices=["user", "analyst", "admin"])
    create.add_argument("--name", default="")
    commands.add_parser("seed-demo")
    load = commands.add_parser("seed-load")
    load.add_argument("--users", type=int, default=200)
    backup_cmd = commands.add_parser("backup")
    backup_cmd.add_argument("--dir", default="var/backups")
    restore_cmd = commands.add_parser("restore")
    restore_cmd.add_argument("file")
    commands.add_parser("check")
    args = parser.parse_args()

    if args.command == "init":
        bootstrap.init_db(create_tables=os.getenv("FH_AUTO_CREATE_TABLES", "true") == "true")
        print("Система подготовлена к работе")
    elif args.command == "create-user":
        db = SessionLocal()
        try:
            bootstrap.create_user(db, args.email, args.password, args.role, args.name)
            db.commit()
        finally:
            db.close()
        print(f"Учетная запись {args.email} ({args.role}) создана")
    elif args.command == "seed-demo":
        bootstrap.init_db(create_tables=os.getenv("FH_AUTO_CREATE_TABLES", "true") == "true")
        seed_demo()
    elif args.command == "seed-load":
        seed_load(args.users)
    elif args.command == "backup":
        backup(args.dir)
    elif args.command == "restore":
        restore(args.file)
    elif args.command == "check":
        sys.exit(check())


if __name__ == "__main__":
    main()
