"""Упрощенный запуск серверной части на одном компьютере без Docker.

Сценарий предназначен для ознакомления с системой и отладки мобильного приложения:
данные хранятся в файле SQLite, фоновые задачи и модели машинного обучения выполняются
внутри процесса серверного приложения, PostgreSQL и Redis не требуются.

Запуск:  python run_local.py [--port 8000] [--no-demo] [--offline]

    --no-demo   не создавать демонстрационные учетные записи и данные;
    --offline   не обращаться к внешним источникам курсов валют и котировок
                (используются встроенные значения).

Для постоянной эксплуатации применяется развертывание в Docker Compose (docker-compose.yml).
"""
from __future__ import annotations

import argparse
import os
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
DATA = ROOT / "var" / "local"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--no-demo", action="store_true")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()

    DATA.mkdir(parents=True, exist_ok=True)
    os.chdir(ROOT)
    defaults = {
        "FH_ENVIRONMENT": "dev",
        "FH_DATABASE_URL": f"sqlite:///{(DATA / 'finhelper.db').as_posix()}",
        "FH_ML_MODELS_DIR": str(DATA / "models"),
        "FH_STORAGE_DIR": str(DATA / "storage"),
        "FH_REQUIRE_EMAIL_CONFIRMATION": "false",
        "FH_AUTO_CREATE_TABLES": "true",
        "FH_RUN_SCHEDULER": "true",
    }
    if args.offline:
        defaults.update({"FH_RATES_PROVIDER": "static", "FH_QUOTES_PROVIDER": "static"})
    for name, value in defaults.items():
        os.environ.setdefault(name, value)

    first_run = not (DATA / "finhelper.db").exists()

    import uvicorn

    from app import manage
    from app.bootstrap import init_db

    init_db()
    if first_run and not args.no_demo:
        print("Первый запуск: создаются демонстрационные учетные записи и данные...")
        manage.seed_demo()
    os.environ["FH_INIT_ON_START"] = "false"
    print(f"\nСерверная часть запущена: http://localhost:{args.port}"
          f"\nОписание программного интерфейса: http://localhost:{args.port}/docs"
          "\nОстановка – Ctrl+C\n")
    uvicorn.run("app.main:app", host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
