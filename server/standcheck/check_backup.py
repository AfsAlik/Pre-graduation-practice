"""Проверка резервного копирования и восстановления базы данных (ПМИ, п. 4.1.4 а).

Создает резервную копию рабочей базы данных стенда, восстанавливает ее в отдельную
базу данных и сравнивает число строк и контрольные суммы основных таблиц.

Запуск:
    python standcheck/check_backup.py --db-url postgresql://user:pass@host/finhelper \
        --restore-url postgresql://user:pass@host/finhelper_restore --out var/backup_check.json
База данных для восстановления должна существовать и быть пустой.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
import time
from pathlib import Path

import psycopg
from sqlalchemy.engine import make_url

TABLES = ["users", "accounts", "categories", "transactions", "budgets", "goals",
          "goal_contributions", "investment_trades", "recommendations", "notifications", "audit_log"]
CHECKSUMS = {"transactions": "coalesce(sum(amount), 0)", "accounts": "coalesce(sum(initial_balance), 0)",
             "goal_contributions": "coalesce(sum(amount), 0)", "users": "coalesce(sum(sync_rev), 0)"}


def snapshot(url: str) -> dict:
    """Число строк и контрольные суммы таблиц."""
    result = {}
    with psycopg.connect(url) as connection:
        for table in TABLES:
            expression = CHECKSUMS.get(table, "0")
            count, checksum = connection.execute(
                f"SELECT count(*), {expression} FROM {table}").fetchone()  # noqa: S608
            result[table] = {"rows": count, "checksum": str(checksum)}
    return result


def pg_args(url: str) -> tuple[dict, list[str]]:
    parsed = make_url(url)
    env = {**os.environ, "PGPASSWORD": parsed.password or ""}
    return env, ["-h", parsed.host or "localhost", "-p", str(parsed.port or 5432),
                 "-U", parsed.username or "postgres", "-d", parsed.database]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--restore-url", required=True)
    parser.add_argument("--out", default="var/backup_check.json")
    args = parser.parse_args()

    before = snapshot(args.db_url)
    dump = Path(tempfile.mkdtemp()) / "finhelper.dump"
    env, source = pg_args(args.db_url)
    started = time.monotonic()
    subprocess.run(["pg_dump", *source, "-Fc", "-f", str(dump)], env=env, check=True)
    backup_seconds = time.monotonic() - started
    env, target = pg_args(args.restore_url)
    started = time.monotonic()
    subprocess.run(["pg_restore", *target, "--no-owner", "-1", str(dump)], env=env, check=True)
    restore_seconds = time.monotonic() - started
    after = snapshot(args.restore_url)

    result = {
        "backup_bytes": dump.stat().st_size, "backup_seconds": round(backup_seconds, 2),
        "restore_seconds": round(restore_seconds, 2), "identical": before == after,
        "tables": {t: {"source": before[t], "restored": after[t]} for t in TABLES},
        "rows_total": sum(v["rows"] for v in before.values()),
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Копия: {result['backup_bytes']} байт за {result['backup_seconds']} с; восстановление "
          f"за {result['restore_seconds']} с; строк в проверенных таблицах: {result['rows_total']}; "
          f"данные совпадают: {'да' if result['identical'] else 'НЕТ'}")


if __name__ == "__main__":
    main()
