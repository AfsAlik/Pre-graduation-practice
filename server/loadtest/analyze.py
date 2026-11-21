"""Обработка журнала запросов нагрузочного тестирования.

Для каждого вида запросов рассчитываются число запросов, среднее время, 95-й
перцентиль, доля запросов, выполненных за время, установленное п. 4.2.2 ТЗ, и доля ошибок.

Запуск:  python loadtest/analyze.py var/load/main.csv [--users 100] [--windows var/load/retrain.json]
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import defaultdict
from datetime import UTC, datetime

# Предельное время по видам запросов, мс (п. 4.2.2.2, 4.2.2.7, 4.2.2.8, 4.2.2.9 ТЗ)
LIMITS = {"GET /reports": 5000, "GET /recommendations": 10000, "GET /forecast": 10000,
          "GET /investments/portfolio": 3000}
DEFAULT_LIMIT = 2000
BASIC = "основные запросы (п. 4.2.2.2)"


def percentile(values: list[float], share: float) -> float:
    """Перцентиль методом ближайшего ранга."""
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[max(math.ceil(share * len(ordered)) - 1, 0)]


def summarize(rows: list[dict]) -> dict:
    groups: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        groups[row["name"]].append(row)
        if row["name"] not in LIMITS and row["name"] != "POST /auth/login":
            groups[BASIC].append(row)
        groups["все запросы"].append(row)
    result = {}
    for name, items in groups.items():
        times = [r["ms"] for r in items]
        limit = LIMITS.get(name, DEFAULT_LIMIT)
        result[name] = {
            "requests": len(items),
            "mean_ms": round(sum(times) / len(times), 1),
            "p95_ms": round(percentile(times, 0.95), 1),
            "p99_ms": round(percentile(times, 0.99), 1),
            "max_ms": round(max(times), 1),
            "limit_ms": limit,
            "within_limit_percent": round(sum(1 for t in times if t <= limit) / len(times) * 100, 2),
            "errors_percent": round(sum(1 for r in items if not r["ok"]) / len(items) * 100, 3),
        }
    return result


def load(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as source:
        return [{"time": float(r["time"]), "name": r["name"], "ms": float(r["response_ms"]),
                 "ok": r["ok"] == "1", "users": int(r["users"])} for r in csv.DictReader(source)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("log")
    parser.add_argument("--users", type=int, nargs="*", help="уровни нагрузки (число пользователей)")
    parser.add_argument("--windows", help="файл JSON с интервалами повторного обучения модели")
    parser.add_argument("--out")
    args = parser.parse_args()
    rows = load(args.log)
    report: dict = {"started_at": datetime.fromtimestamp(rows[0]["time"], UTC).isoformat(),
                    "finished_at": datetime.fromtimestamp(rows[-1]["time"], UTC).isoformat(),
                    "total_requests": len(rows),
                    "duration_minutes": round((rows[-1]["time"] - rows[0]["time"]) / 60, 1)}
    levels = args.users or sorted({r["users"] for r in rows if r["users"] and r["users"] % 50 == 0})
    for level in levels:
        steady = [r for r in rows if r["users"] == level]
        if steady:
            report[f"users_{level}"] = {
                "minutes": round((steady[-1]["time"] - steady[0]["time"]) / 60, 1),
                "requests_per_second": round(
                    len(steady) / max(steady[-1]["time"] - steady[0]["time"], 1), 2),
                "by_request": summarize(steady)}
    if args.windows:
        with open(args.windows, encoding="utf-8") as source:
            windows = json.load(source)["windows"]
        level = levels[0]
        steady = [r for r in rows if r["users"] == level and r["name"] not in LIMITS
                  and r["name"] != "POST /auth/login"]
        inside = [r for r in steady if any(w["start"] <= r["time"] <= w["end"] for w in windows)]
        outside = [r for r in steady if not any(w["start"] <= r["time"] <= w["end"] for w in windows)]
        p95_in = percentile([r["ms"] for r in inside], 0.95)
        p95_out = percentile([r["ms"] for r in outside], 0.95)
        report["retraining"] = {
            "trainings": len(windows),
            "training_seconds_total": round(sum(w["end"] - w["start"] for w in windows), 1),
            "requests_during_training": len(inside), "requests_without_training": len(outside),
            "p95_during_training_ms": round(p95_in, 1), "p95_without_training_ms": round(p95_out, 1),
            "p95_change_percent": round((p95_in / p95_out - 1) * 100, 1) if p95_out else None,
            "mean_during_training_ms": round(sum(r["ms"] for r in inside) / max(len(inside), 1), 1),
            "mean_without_training_ms": round(sum(r["ms"] for r in outside) / max(len(outside), 1), 1),
        }
    text = json.dumps(report, ensure_ascii=False, indent=1)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as target:
            target.write(text)
    for key, value in report.items():
        if key.startswith("users_"):
            print(f"\n== {key[6:]} пользователей: {value['minutes']} мин, "
                  f"{value['requests_per_second']} запросов/с")
            for name, s in sorted(value["by_request"].items()):
                print(f"  {name:<36} n={s['requests']:<6} среднее {s['mean_ms']:>7} мс  "
                      f"p95 {s['p95_ms']:>7} мс  в норме {s['within_limit_percent']:>6} %  "
                      f"ошибок {s['errors_percent']} %")
    if "retraining" in report:
        print("\nПовторное обучение:", json.dumps(report["retraining"], ensure_ascii=False))


if __name__ == "__main__":
    main()
