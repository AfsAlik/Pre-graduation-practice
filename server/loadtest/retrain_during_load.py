"""Запуск повторного обучения модели во время нагрузочного теста (ПМИ, п. 4.1.15 г).

В течение заданного времени последовательно запускает обучение модели категоризации
и записывает интервалы обучения в файл JSON для сравнения времени отклика.

Запуск:  python loadtest/retrain_during_load.py --delay 900 --duration 300 --out var/load/retrain.json
"""
from __future__ import annotations

import argparse
import json
import time

import httpx

API = "/api/v1"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8000")
    parser.add_argument("--email", default="analyst@example.com")
    parser.add_argument("--password", default="Demo-2026-pass")
    parser.add_argument("--delay", type=float, default=0, help="ожидание перед первым обучением, с")
    parser.add_argument("--duration", type=float, default=300, help="в течение какого времени обучать, с")
    parser.add_argument("--out", default="var/load/retrain.json")
    args = parser.parse_args()
    time.sleep(args.delay)
    windows = []
    deadline = time.time() + args.duration
    while time.time() < deadline:
        client = httpx.Client(base_url=args.base, timeout=60)
        token = client.post(f"{API}/auth/login", json={"email": args.email,
                                                       "password": args.password}).json()["access_token"]
        client.headers["Authorization"] = f"Bearer {token}"
        start = time.time()
        model = client.post(f"{API}/ml/models/train").json()
        while model["status"] == "training":
            time.sleep(1)
            model = client.get(f"{API}/ml/models/{model['id']}").json()
        windows.append({"start": start, "end": time.time(), "version": model["version"],
                        "status": model["status"], "duration_seconds": model["duration_seconds"]})
        client.close()
    with open(args.out, "w", encoding="utf-8") as target:
        json.dump({"windows": windows}, target, ensure_ascii=False, indent=1)
    print(f"Обучений: {len(windows)}")


if __name__ == "__main__":
    main()
