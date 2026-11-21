"""Исполнитель фоновых задач и планировщик.

Запуск: python -m app.worker
Процесс читает очередь задач Redis (FH_REDIS_URL) и выполняет периодические
задачи по расписанию. Может быть запущено несколько экземпляров.
"""
import json
import logging
import signal

from .services import scheduler as scheduler_module
from .services.tasks import QUEUE_KEY, get_redis, run_task

log = logging.getLogger("app.worker")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    scheduler = scheduler_module.Scheduler()
    scheduler.start_thread()
    client = get_redis()
    if client is None:
        log.warning("FH_REDIS_URL не задан: выполняются только периодические задачи")
    running = True

    def stop(*_):
        nonlocal running
        running = False
        scheduler.stop()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    log.info("Исполнитель фоновых задач запущен")
    while running:
        if client is None:
            signal.pause()
            continue
        try:
            item = client.blpop(QUEUE_KEY, timeout=5)
        except Exception:
            log.exception("Ошибка чтения очереди задач")
            continue
        if item is None:
            continue
        payload = json.loads(item[1])
        log.info("Задача %s", payload["name"])
        run_task(payload["name"], payload.get("kwargs", {}))


if __name__ == "__main__":
    main()
