"""Загрузка результатов предварительных комплексных испытаний и вывод оценок.

Общий модуль для раздела 3.6 отчета (results_text.py) и протокола испытаний
(protocol.py): оба используют одни и те же измеренные значения и оценки.

Источники (каталог results):
    standcheck.json    – сценарии на тестовом стенде (server/standcheck/run_checks.py);
    backup_check.json  – резервное копирование и восстановление (server/standcheck/check_backup.py);
    load_steps.json    – ступенчатая нагрузка 50/100/200 пользователей (server/loadtest/analyze.py);
    load_main.json     – основной тест 100 пользователей с повторным обучением модели;
    load_two.json      – 200 пользователей на двух экземплярах серверного приложения;
    mobile.json        – сборка и автоматизированные тесты мобильного приложения.
Файлы с ключом "smoke_sample" или "placeholder" содержат пробные данные: в строгом
режиме (strict=True) загрузка таких файлов прерывается ошибкой.

Оценки: «соответствует», «не соответствует», «не проводилась»; проверка, выполненная
не полностью, оценивается как «соответствует в части …; … не проводилась» с указанием
выполненной части. Измеренные значения берутся только из файлов результатов; в тексте
модуля – только требования ТЗ и ПМИ и описания проверок.
"""
from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS_DIR = os.path.join(HERE, "results")
LOCAL_TZ = ZoneInfo("Asia/Yekaterinburg")

FULL, PARTIAL, FAIL, NOT_DONE = "full", "partial", "fail", "not_done"
VERDICT_TEXT = {FULL: "соответствует", FAIL: "не соответствует", NOT_DONE: "не проводилась"}
STAGE_VERDICT_TEXT = {FULL: "соответствует", PARTIAL: "соответствует частично",
                      FAIL: "не соответствует", NOT_DONE: "не проводилась"}

# Пределы по ТЗ, мс (п. 4.2.2.2, 4.2.2.7–4.2.2.9) и обозначения видов запросов в журналах
BASIC = "основные запросы (п. 4.2.2.2)"
ALL = "все запросы"
LOGIN = "POST /auth/login"
REQUEST_TITLES = {
    BASIC: "Основные запросы – всего",
    "POST /transactions": "Добавление транзакции",
    "GET /transactions": "История операций",
    "GET /accounts": "Список счетов",
    "GET /dashboard": "Главный экран (дашборд)",
    "GET /budgets/status": "Состояние бюджетов",
    "GET /goals/progress": "Прогресс целей",
    "GET /sync/status": "Состояние синхронизации",
    "POST /sync": "Синхронизация",
    "GET /reports": "Аналитический отчет",
    "GET /recommendations": "Рекомендации",
    "GET /forecast": "Прогноз",
    "GET /investments/portfolio": "Портфель (сохраненные котировки)",
    LOGIN: "Вход в систему",
    ALL: "Все запросы",
}
REQUEST_ORDER = list(REQUEST_TITLES)
TZ_POINT = {BASIC: "4.2.2.2", "GET /reports": "4.2.2.8", "GET /recommendations": "4.2.2.9",
            "GET /forecast": "4.2.2.9", "GET /investments/portfolio": "4.2.2.7"}

# Условия проведения испытаний (сведения о стенде, а не результаты измерений)
STAND = {
    "hardware": "одна виртуальная машина (4 виртуальных ядра процессора, 15 ГБ оперативной памяти, "
                "операционная система Linux)",
    "software": "PostgreSQL 16, Redis, Python 3.13; серверное приложение под управлением gunicorn, "
                "исполнитель фоновых задач и сервис машинного обучения запущены как процессы "
                "операционной системы",
    "sources": "официальные курсы валют Банка России, котировки информационно-статистического "
               "сервера Московской биржи, цены криптовалют сервиса CoinGecko",
    "data": "демонстрационные учетные записи с ролями «Пользователь», «Аналитик» и «Администратор», "
            "учетные записи, создаваемые сценариями испытаний, и 200 учетных записей для "
            "нагрузочного тестирования, для каждой из которых сформировано около 190 операций",
}
# Отличия стенда от п. 1.2 ПМИ: (компонент по ПМИ, фактически на стенде)
STAND_DEVIATIONS = [
    ("Docker и Docker Compose", "конфигурация Docker Compose проверена командой docker compose "
     "config, контейнеры не запускались; те же компоненты запущены как процессы операционной системы"),
    ("PostgreSQL 15 или выше", "PostgreSQL 16, один узел без репликации"),
    ("Redis 7 или выше", "Redis, один узел"),
    ("Серверное приложение и сервис машинного обучения", "развернуты, работают"),
    ("S3-совместимое объектное хранилище", "заменено каталогом локальной файловой системы"),
    ("Prometheus и Grafana", "не запускались; проверялись только адрес /metrics серверного "
     "приложения и встроенный раздел «Мониторинг»"),
    ("Сертификат TLS, протокол HTTPS", "не настроены: обмен по протоколу HTTP на локальном адресе"),
    ("Источники курсов валют и котировок", "подключены: Банк России, Московская биржа, CoinGecko"),
    ("Firebase Cloud Messaging, почтовый сервер", "не подключены: уведомления и письма записываются "
     "в журнал исполнителя фоновых задач"),
    ("Тестовые смартфоны и эмуляторы", "отсутствуют: мобильное приложение проверено только сборкой, "
     "модульными тестами и снимками экранов, построенными автоматизированными тестами"),
    ("Средства нагрузочного тестирования", "Locust"),
    ("Анализатор сетевого трафика, TalkBack, Accessibility Scanner, Jetpack Macrobenchmark, "
     "UI Automator", "не применялись"),
]

M_CODE = "проверено анализом исходного текста"
DISPLAY = "соответствует в части данных сервера; отображение на устройстве не проверялось"
M_TEST = "подтверждено автоматизированным тестом"
M_DOC = "проверено анализом документации"


class SampleDataError(RuntimeError):
    """В строгом режиме обнаружены пробные данные вместо результатов испытаний."""


# ----------------------------------------------------------------------------- форматирование
def ru(text) -> str:
    """Десятичная запятая и даты в формате ДД.ММ.ГГГГ."""
    text = re.sub(r"(?<=\d)\.(?=\d)", ",", str(text))
    return re.sub(r"\b(\d{4})-(\d{2})-(\d{2})\b", r"\3.\2.\1", text)


def num(value, digits: int = 1) -> str:
    if value is None:
        return "–"
    if isinstance(value, int) and digits == 0:
        return f"{value:,}".replace(",", " ")
    text = f"{float(value):,.{digits}f}".replace(",", " ").replace(".", ",")
    return text


def pct(value, digits: int = 1) -> str:
    return "–" if value is None else f"{num(value, digits)} %"


def local(dt: datetime) -> datetime:
    return dt.astimezone(LOCAL_TZ)


def parse_dt(value: str) -> datetime:
    dt = datetime.fromisoformat(value)
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
          "октября", "ноября", "декабря"]


def long_date(day: date) -> str:
    return f"«{day.day:02d}» {MONTHS[day.month - 1]} {day.year} г."


# ----------------------------------------------------------------------------- модель
@dataclass
class Item:
    stage: int
    letter: str
    title: str
    verdict: str
    measured: str = ""
    partial: str = ""  # для PARTIAL – полная формулировка оценки

    @property
    def verdict_text(self) -> str:
        return self.partial if self.verdict == PARTIAL else VERDICT_TEXT[self.verdict]


@dataclass
class Stage:
    number: int
    title: str
    tz: str
    pmi: str
    items: list[Item] = field(default_factory=list)

    @property
    def verdict(self) -> str:
        kinds = {i.verdict for i in self.items}
        if not kinds or kinds == {NOT_DONE}:
            return NOT_DONE
        if FAIL in kinds:
            return FAIL
        if kinds == {FULL}:
            return FULL
        return PARTIAL

    @property
    def verdict_text(self) -> str:
        return STAGE_VERDICT_TEXT[self.verdict]

    def count(self, verdict: str) -> int:
        return sum(1 for i in self.items if i.verdict == verdict)


@dataclass
class Improvement:
    text: str
    deadline: str


# Рекомендуемые сроки: после испытаний, до плановых предварительных испытаний по
# календарному плану ТЗ (19.02–26.02.2027) и в период опытной эксплуатации (до 21.03.2027)
D_PRACTICE = "до 25.12.2026"
D_JAN = "до 29.01.2027"
D_FEB = "до 12.02.2027"
D_TRIAL = "в период опытной эксплуатации, до 21.03.2027"


# ----------------------------------------------------------------------------- загрузка
def _read(name: str):
    path = os.path.join(RESULTS_DIR, name)
    if not os.path.exists(path):
        return None, path
    with open(path, encoding="utf-8") as source:
        return json.load(source), path


class Results:
    """Результаты испытаний, оценки по этапам и перечень доработок."""

    def __init__(self, strict: bool = False):
        self.strict = strict
        self.samples: list[str] = []   # файлы с пробными данными
        self.missing: list[str] = []   # отсутствующие файлы
        self.moments: list[tuple[str, datetime]] = []
        self.standcheck = self._load("standcheck.json", timestamps=True)
        self.backup = self._load("backup_check.json")
        self.steps = self._load("load_steps.json", timestamps=True)
        self.main = self._load("load_main.json", timestamps=True)
        self.two = self._load("load_two.json", timestamps=True)
        self.mobile = self._load("mobile.json")
        if strict and (self.samples or self.missing):
            raise SampleDataError(
                "Строгий режим: результаты испытаний неполные. Пробные данные: "
                f"{', '.join(self.samples) or 'нет'}; отсутствуют: {', '.join(self.missing) or 'нет'}")
        self.checks: dict[tuple[int, str], dict] = {}
        self.aborted: list[dict] = []
        for check in (self.standcheck or {}).get("results", []):
            if check.get("item") in ("–", "-"):
                self.aborted.append(check)
            else:
                self.checks[(int(check["stage"]), check["item"])] = check
        self.stages = self._build_stages()
        self.improvements = self._build_improvements()

    def _load(self, name: str, timestamps: bool = False):
        data, path = _read(name)
        if data is None:
            self.missing.append(name)
            return None
        if data.get("smoke_sample") or data.get("placeholder"):
            self.samples.append(name)
        elif timestamps:
            if data.get("started_at"):
                self.moments.append((name, parse_dt(data["started_at"])))
                self.moments.append((name, parse_dt(data.get("finished_at") or data["started_at"])))
            else:  # файлы нагрузочного тестирования не содержат отметок времени
                self.moments.append((name, datetime.fromtimestamp(os.path.getmtime(path), timezone.utc)))
        return data

    @property
    def is_sample(self) -> bool:
        return bool(self.samples or self.missing)

    # ------------------------------------------------------------------ сроки
    @property
    def period(self) -> tuple[date, date] | None:
        if not self.moments:
            return None
        days = [local(m).date() for _, m in self.moments]
        return min(days), max(days)

    @property
    def stand_window(self) -> tuple[datetime, datetime] | None:
        if not self.standcheck:
            return None
        return (local(parse_dt(self.standcheck["started_at"])),
                local(parse_dt(self.standcheck["finished_at"])))

    # ------------------------------------------------------------------ доступ к данным
    def check(self, stage: int, letter: str) -> dict | None:
        return self.checks.get((stage, letter))

    def value(self, stage: int, letter: str, key: str, default=None):
        check = self.check(stage, letter)
        return check.get(key, default) if check else default

    def detail_number(self, stage: int, letter: str, pattern: str) -> float | None:
        check = self.check(stage, letter)
        if not check:
            return None
        match = re.search(pattern, check.get("detail", ""))
        return float(match.group(1)) if match else None

    @property
    def model(self) -> dict | None:
        return self.value(9, "д", "model")

    @property
    def quality(self) -> dict | None:
        return self.value(9, "д", "quality")

    @property
    def monitoring(self) -> dict | None:
        return self.value(12, "ж", "monitoring")

    @staticmethod
    def level(report: dict | None, users: int) -> dict | None:
        return (report or {}).get(f"users_{users}")

    @staticmethod
    def request(level: dict | None, name: str) -> dict | None:
        return (level or {}).get("by_request", {}).get(name)

    # ------------------------------------------------------------------ сценарии стенда
    def scripted(self, stage: int, letter: str, title: str, partial: str | None = None,
                 measured=None) -> Item:
        """Пункт, проверенный сценарием на стенде (standcheck.json)."""
        check = self.check(stage, letter)
        if check is None:
            aborted = [a for a in self.aborted if int(a["stage"]) == stage]
            reason = (f"сценарий этапа прерван ошибкой: {aborted[0]['detail']}" if aborted
                      else "результат сценария отсутствует")
            return Item(stage, letter, title, FAIL if aborted else NOT_DONE, ru(reason))
        text = measured(check) if callable(measured) else ru(clean_detail(check.get("detail", "")))
        if check["result"] == "нет":
            return Item(stage, letter, title, FAIL, text)
        if check["result"] != "да":
            return Item(stage, letter, title, NOT_DONE, text)
        if partial:
            return Item(stage, letter, title, PARTIAL, text, partial)
        return Item(stage, letter, title, FULL, text)

    # ------------------------------------------------------------------ этапы
    def _build_stages(self) -> list[Stage]:
        return [self._stage_1(), self._stage_2(), self._stage_3(), self._stage_4(), self._stage_5(),
                self._stage_6(), self._stage_7(), self._stage_8(), self._stage_9(), self._stage_10(),
                self._stage_11(), self._stage_12(), self._stage_13(), self._stage_14(),
                self._stage_15(), self._stage_16()]

    def _mobile_ok(self) -> bool | None:
        m = self.mobile
        if not m or m.get("placeholder") or m.get("build_ok") is None:
            return None
        return bool(m.get("build_ok")) and not m.get("unit_tests_failed")

    def _stage_1(self) -> Stage:
        s = Stage(1, "Комплектность предъявляемой системы", "4.1.1", "4.1.1")

        def components(c):
            names = {"БД": "база данных", "сервис ML": "сервис машинного обучения",
                     "курсы валют актуальны": "актуальность курсов валют"}
            found = re.findall(r"([^:,]+): (True|False)", c["detail"])
            return "; ".join(f"{names.get(k.strip(), k.strip())} – {'да' if v == 'True' else 'нет'}"
                             for k, v in found) or ru(c["detail"])
        m = self.mobile or {}
        ok = self._mobile_ok()
        if ok is None:
            s.items.append(Item(1, "а", "Тестовая сборка мобильного приложения", NOT_DONE,
                                "результаты сборки мобильного приложения не представлены"))
        else:
            text = (f"отладочная сборка APK {'выполнена' if m.get('build_ok') else 'не выполнена'}"
                    + (f", размер {num(m.get('apk_size_mb'), 1)} МБ" if m.get("apk_size_mb") else "")
                    + (f"; minSdk {m.get('min_sdk')}, targetSdk {m.get('target_sdk')}"
                       if m.get("min_sdk") else "")
                    + f"; модульных тестов {m.get('unit_tests_total')}, из них не пройдено "
                      f"{m.get('unit_tests_failed')}")
            s.items.append(Item(1, "а", "Тестовая сборка мобильного приложения",
                                FULL if ok else FAIL, text))
        s.items.append(self.scripted(1, "а", "Серверная часть доступна, база данных подключена",
                                     measured=lambda c: "сервер отвечает на запрос готовности, "
                                                        "подключение к базе данных подтверждено"))
        s.items[-1].letter = "б"
        s.items.append(self.scripted(1, "б", "Программный интерфейс восьми подсистем опубликован"))
        s.items[-1].letter = "в"
        s.items.append(self.scripted(1, "в", "Компоненты серверной части работоспособны по данным "
                                             "мониторинга", measured=components))
        s.items[-1].letter = "г"
        s.items.append(self.scripted(1, "г", "Сервис машинного обучения имеет активную модель"))
        s.items[-1].letter = "д"
        if ok is None:
            s.items.append(Item(1, "е", "Экраны и окна мобильного приложения по таблице 2 ПМИ",
                                NOT_DONE, "результаты автоматизированных тестов мобильного "
                                          "приложения не представлены"))
        elif m.get("screenshot_tests"):
            s.items.append(Item(
                1, "е", "Экраны и окна мобильного приложения по таблице 2 ПМИ", PARTIAL,
                f"снимков экранов, построенных автоматизированными тестами без устройства: "
                f"{m.get('screenshot_tests')}",
                "соответствует в части наличия экранов (снимки экранов); открытие экранов на "
                "устройстве не проводилось"))
        else:
            s.items.append(Item(1, "е", "Экраны и окна мобильного приложения по таблице 2 ПМИ",
                                NOT_DONE, "снимки экранов не построены"))
        return s

    def _stage_2(self) -> Stage:
        s = Stage(2, "Наличие необходимой документации", "6.3", "4.1.2")
        s.items.append(Item(
            2, "а", "Номенклатура документации стадии «Технорабочий проект» по п. 6.3 ТЗ", FAIL,
            "имеются: техническое задание, программа и методика предварительных испытаний, "
            "описание системы (раздел 3 отчета), руководства администратора и пользователя "
            "(раздел 4 отчета), спецификация OpenAPI (server/docs/openapi.json); отсутствуют: "
            "пояснительная записка к техническому проекту, общее описание системы и описание "
            "программного обеспечения как самостоятельные документы, программы и методики опытной "
            "эксплуатации и приемочных испытаний, акт завершения работ стадии"))
        return s

    def _stage_3(self) -> Stage:
        s = Stage(3, "Соответствие документации требованиям ТЗ в части стандартов", "9.2", "4.1.3")
        s.items.append(Item(
            3, "а", "Руководство администратора содержит сведения п. 9.2.5 ТЗ", FULL,
            "подразделы 4.1.1–4.1.7 отчета: действия до установки, установка и возможные проблемы, "
            "параметры и типовая конфигурация, резервное копирование и восстановление, журналы, "
            f"мониторинг и модели машинного обучения ({M_DOC})"))
        s.items.append(Item(
            3, "б", "Оформление документов по ГОСТ Р 59795-2021 (п. 9.2.1–9.2.4 ТЗ)", FAIL,
            "описание системы и руководства включены в отчет как разделы 3 и 4 и оформлены по "
            "ГОСТ 7.32-2017, а не как самостоятельные документы по ГОСТ Р 59795-2021"))
        s.items.append(Item(
            3, "в", "Оформление ПМИ по ГОСТ Р 59792-2021 и ГОСТ Р 59795-2021 (п. 9.2.6 ТЗ)",
            NOT_DONE, "проверка структуры ПМИ по пунктам стандартов в ходе испытаний не выполнялась"))
        return s

    def _stage_4(self) -> Stage:
        s = Stage(4, "Надежность", "4.4.3, 4.4.8", "4.1.4")
        b = self.backup
        if b is None:
            s.items.append(Item(4, "а", "Резервное копирование и восстановление базы данных",
                                NOT_DONE, "результаты проверки отсутствуют"))
        else:
            tables = b.get("tables", {})
            text = (f"копия {num(b['backup_bytes'] / 1024 / 1024, 2)} МБ создана за "
                    f"{num(b['backup_seconds'], 2)} с, восстановлена в отдельную базу данных за "
                    f"{num(b['restore_seconds'], 2)} с; число строк и контрольные суммы "
                    f"{len(tables)} таблиц ({num(b['rows_total'], 0)} строк) "
                    f"{'совпадают' if b['identical'] else 'НЕ совпадают'}; ежесуточное копирование "
                    f"по расписанию предусмотрено параметром backup ({M_CODE})")
            s.items.append(Item(4, "а", "Резервное копирование и восстановление базы данных",
                                FULL if b["identical"] else FAIL, text))
        s.items.append(Item(4, "б", "Переключение на резервный узел базы данных без потери данных",
                            NOT_DONE, "репликация базы данных и автоматическое переключение на "
                                      "стенде не настроены"))
        s.items.append(Item(4, "в", "Сохранность данных при аварийном завершении мобильного "
                                    "приложения", NOT_DONE, "мобильное устройство отсутствовало"))
        resend = self.check(10, "г")
        if resend and resend["result"] == "да":
            s.items.append(Item(
                4, "г", "Сетевой сбой при отправке данных: повторная отправка без дублирования",
                PARTIAL, "повторная отправка очереди операций дубликатов не создает (этап 10, п. г)",
                "соответствует в части серверной обработки повторной отправки; отключение сети на "
                "устройстве не проводилось"))
        else:
            s.items.append(Item(4, "г", "Сетевой сбой при отправке данных: повторная отправка без "
                                        "дублирования", FAIL if resend else NOT_DONE,
                                "см. этап 10, п. г"))
        mon = self.monitoring
        if mon:
            window = self.stand_window
            ok = mon["availability_percent"] >= 99
            s.items.append(Item(
                4, "д", "Доступность серверной части за период испытаний не менее 99 %",
                PARTIAL if ok else FAIL,
                f"по данным встроенного мониторинга – {pct(mon['availability_percent'])} "
                f"({mon['health_checks']} проверок работоспособности за {mon['period_hours']} ч до "
                f"{window[1]:%H:%M} {window[1]:%d.%m.%Y})" if window else "",
                "соответствует по данным встроенного мониторинга за время работы стенда; "
                "непрерывный мониторинг в течение всего периода испытаний не проводился"))
        else:
            s.items.append(Item(4, "д", "Доступность серверной части за период испытаний не менее "
                                        "99 %", NOT_DONE, "данные мониторинга отсутствуют"))
        return s

    def _stage_5(self) -> Stage:
        s = Stage(5, "Функции подсистемы учета транзакций и счетов", "4.1.1.1, 4.2.1.1", "4.1.5")
        sc = self.scripted
        s.items += [
            sc(5, "а", "Ручной ввод расхода, обновление баланса и бюджета",
               "соответствует в части серверной обработки; сообщение «Транзакция добавлена» на "
               "экране не проверялось"),
            sc(5, "б", "Ввод по шаблону, по фотографии чека и голосом",
               "соответствует в части ввода по шаблону и серверного разбора текста чека и "
               "голосовой фразы; распознавание фотографии и речи на устройстве не проводилось"),
            sc(5, "в", "Точность автоматической категоризации не менее 80 % (п. 4.3.1.3 ТЗ)",
               measured=lambda c: (f"{c['correct']} из {c['total']} операций контрольной выборки "
                                   f"({pct(c['accuracy'] * 100)}); подробно – в таблице "
                                   "показателей качества")),
            sc(5, "г", "Пользовательская категория и подкатегория",
               measured=lambda c: "созданы категория «Хобби» и подкатегория «Рыбалка», транзакция "
                                  "сохранена с подкатегорией"),
            sc(5, "д", "Три счета разных типов и валют: пополнение, списание, перевод"),
            sc(5, "е", "Фильтры по периоду, категории, счету, сумме и поиск"),
            sc(5, "ж", "Удаление и восстановление транзакции со всеми атрибутами"),
            sc(5, "з", "Регулярный платеж: напоминание о дате платежа",
               "соответствует в части формирования напоминания; доставка на устройство не "
               "проверялась",
               measured=lambda c: ru(f"сформировано уведомление «{c['detail']}» (задача "
                                     "напоминаний запущена администратором)")),
        ]
        return s

    def _stage_6(self) -> Stage:
        s = Stage(6, "Функции подсистемы бюджетирования и целей", "4.1.1.2, 4.2.1.2", "4.1.6")
        sc = self.scripted
        delivery = ("соответствует в части формирования и передачи уведомления (в журнал); "
                    "доставка на устройство не проверялась")
        s.items += [
            sc(6, "а", "Лимит 10 000 руб. на «Кафе» и еще четыре лимита на разные периоды",
               measured=lambda c: "сохранено пять лимитов (неделя, месяц, год)"),
            sc(6, "б", "Расходы 8 000 руб.: уведомление о приближении к лимиту", delivery),
            sc(6, "в", "Еще 3 000 руб.: уведомление о превышении, выделение категории",
               delivery + "; выделение цветом на экране не проверялось"),
            sc(6, "г", "Цель «Отпуск»: 100 000 руб., 6 месяцев, высокий приоритет",
               measured=lambda c: "цель создана в состоянии «активна» с высоким приоритетом"),
            sc(6, "д", "Накопление 40 000 руб.: прогресс, прогнозируемая дата, рекомендуемая сумма",
               measured=self._goal_measured),
            sc(6, "е", "Достигнутая цель доступна только для просмотра",
               measured=lambda c: ru(clean_detail(c["detail"]).replace("«achieved»", "«достигнута»"))),
        ]
        return s

    def _goal_measured(self, check: dict) -> str:
        text = ru(clean_detail(check["detail"]))
        found = re.search(r"прогнозируемая дата (\d{4}-\d{2}-\d{2}).*?сумма ([\d.]+)", check["detail"])
        if not (found and self.standcheck):
            return text
        test_day = parse_dt(self.standcheck["started_at"]).date()
        expected = test_day + timedelta(days=45)  # формула (2): T + 60 000 · 30 / 40 000
        months = 60000 / float(found.group(2))
        same = date.fromisoformat(found.group(1)) == expected
        return (text + f"; контрольный расчет по формуле (2) – {expected:%d.%m.%Y} "
                f"({'совпадает' if same else 'НЕ совпадает'}); рекомендуемая сумма равна остатку "
                f"60 000 руб., деленному на {num(months, 0)} мес. до срока")

    def _stage_7(self) -> Stage:
        s = Stage(7, "Функции подсистемы инвестиционного учета", "4.1.1.3, 4.2.1.3", "4.1.7")
        sc = self.scripted
        types = {"stock": "акции", "bond": "облигации", "etf": "фонды", "currency": "валюта",
                 "crypto": "криптовалюта"}
        display = DISPLAY

        def positions(c):
            found = re.search(r"типы: (.*)", c["detail"])
            names = [types.get(t.strip(), t.strip()) for t in found.group(1).split(",")] if found else []
            return f"шесть позиций пяти типов: {', '.join(names)}" if names else ru(c["detail"])

        def control(c):
            found = re.search(r"отклонения ([\d.]+) / ([\d.]+) / ([\d.]+)", c["detail"])
            base = ru(re.sub(r";\s*отклонения.*", "", c["detail"]))
            if found:
                base += (f"; отклонения вложений, стоимости и результата: {ru(found.group(1))} / "
                         f"{ru(found.group(2))} / {ru(found.group(3))} %")
            return base + f"; метод FIFO {M_TEST} test_average_cost_and_fifo_methods"

        def chart(c):
            found = re.search(r"\{'day': (\d+), 'month': (\d+), 'year': (\d+)\}", c["detail"])
            structure = re.search(r"структура: \{(.*)\}", c["detail"])
            text = (f"точек графика за день, месяц и год: {found.group(1)}, {found.group(2)}, "
                    f"{found.group(3)}" if found else "данные графика получены")
            if structure:
                parts = re.findall(r"'(\w+)': ([\d.]+)", structure.group(1))
                text += "; структура портфеля, %: " + ", ".join(
                    f"{types.get(k, k)} {ru(v)}" for k, v in parts)
            return text

        s.items += [
            sc(7, "а", "Сделки не менее чем с пятью позициями разных типов", measured=positions),
            sc(7, "б", "Получение текущих рыночных цен"),
            sc(7, "в", "Отклонение от контрольного расчета по средней себестоимости не более 1 %",
               measured=control),
            sc(7, "г", "График динамики за день, месяц, год и структура портфеля", display,
               measured=chart),
            sc(7, "д", "Карточка актива: история цен и доходность за периоды", display),
            sc(7, "е", "Запрет изменения позиции старше 7 дней с пояснением",
               measured=lambda c: ru(clean_detail(c["detail"])) + " (дата фиксации позиции "
                                   "сдвинута на 8 сут. в тестовой базе данных)"),
        ]
        return s

    def _stage_8(self) -> Stage:
        s = Stage(8, "Функции подсистемы аналитики и отчетности", "4.1.1.4, 4.2.1.4", "4.1.8")
        sc = self.scripted
        display = DISPLAY
        by_account = self.detail_number(8, "г", r"по счету «[^»]+»: ([\d.]+)")
        account_partial = ("соответствует в части фильтра по категории; фильтр по счету проверен "
                           "только на счете без расходов за период" if by_account == 0 else None)
        s.items += [
            sc(8, "а", "Главный экран содержит все блоки сводной информации", display),
            sc(8, "б", "Отчет по категориям расходов за текущий месяц"),
            sc(8, "в", "Итоги отчета совпадают с данными транзакций"),
            sc(8, "г", "Фильтры по счету и категории изменяют данные отчета", account_partial),
            sc(8, "д", "Прогноз остатка средств на конец месяца", display),
        ]
        return s

    def _stage_9(self) -> Stage:
        s = Stage(9, "Функции подсистемы интеллектуальных рекомендаций", "4.1.1.5, 4.2.1.5",
                  "4.1.9")
        sc = self.scripted
        methods = {"exponential_smoothing": "экспоненциальное сглаживание",
                   "holt_winters": "экспоненциальное сглаживание с учетом сезонности",
                   "moving_average": "скользящее среднее"}

        def forecast(c):
            text = clean_detail(c["detail"])
            for key, title in methods.items():
                text = text.replace(f"метод {key}", f"метод – {title}")
            return ru(text)

        def quality(c):
            q, m = c.get("quality") or {}, c.get("model") or {}
            return ru(
                f"accuracy {m.get('accuracy')}, precision {m.get('precision')}, recall "
                f"{m.get('recall')}, F1 {m.get('f1')}; средняя ошибка прогноза расходов "
                f"{q.get('forecast_mape')} %; доля положительных оценок рекомендаций "
                f"{pct((q.get('positive_feedback_share') or 0) * 100, 0)} по "
                f"{q.get('rated_recommendations')} оценкам; срезов динамики показателей: "
                f"{len(q.get('history') or [])}")

        rated = (self.quality or {}).get("rated_recommendations")
        history = len((self.quality or {}).get("history") or [])
        s.items += [
            sc(9, "а", "Рекомендации с пояснением и оценкой экономии"),
            sc(9, "б", "Оценка «Полезно» и применение рекомендации «Установить бюджет»",
               measured=lambda c: ru(clean_detail(c["detail"]).replace(": useful", ": «Полезно»"))),
            sc(9, "в", "Прогноз расходов и доходов с интервалом уверенности", measured=forecast),
            sc(9, "г", "Предупреждение о финансовом риске (кредиты более 40 % дохода, нет резерва)"),
            sc(9, "д", "Показатели качества модели, ошибка прогноза, доля положительных оценок и "
                       "динамика для аналитика",
               f"соответствует в части формирования показателей; доля оценок по {rated} оценкам "
               f"непредставительна, срезов динамики: {history}; отображение на устройстве не "
               "проверялось",
               measured=quality),
            sc(9, "е", "Повторное обучение модели не более 2 ч, показатели обновлены",
               measured=lambda c: ru(c["detail"].replace("активна: True", "версия активна")
                                     .replace("активна: False", "версия НЕ активна"))),
        ]
        return s

    def _stage_10(self) -> Stage:
        s = Stage(10, "Функции подсистемы уведомлений и синхронизации", "4.1.1.6, 4.1.4, 4.2.1.6",
                  "4.1.10")
        sc = self.scripted
        device = "; на устройствах не проводилась"

        def offline(c):
            found = re.search(r"результаты: \[(.*?)\]", c["detail"])
            statuses = re.findall(r"'(\w+)'", found.group(1)) if found else []
            text = (f"из {len(statuses)} операций очереди применено {statuses.count('applied')}, "
                    f"выявлен конфликт – {statuses.count('conflict')}" if statuses else "")
            if c.get("sync_seconds") is not None:
                text += f"; время синхронизации {num(c['sync_seconds'], 3)} с"
            return text or ru(c["detail"])

        s.items += [
            sc(10, "а", "Настройка каналов и порогов уведомлений",
               measured=lambda c: "включены письма, порог уведомления о бюджете изменен на 70 %"),
            sc(10, "б", "Операции автономного режима приняты после восстановления связи",
               "соответствует в части приема сервером очереди автономных операций (имитация "
               "устройства)" + device, measured=offline),
            sc(10, "в", "Конфликт изменений с двух устройств разрешен",
               "соответствует в части разрешения конфликта сервером" + device),
            sc(10, "г", "Повторная отправка очереди без потери и дублирования данных",
               "соответствует в части серверной обработки; на устройстве не проводилась"),
            sc(10, "д", "Уведомление о превышении лимита по каналам push и e-mail",
               "соответствует в части формирования и передачи уведомления (в журнал); доставка на "
               "устройство и в почтовый ящик не проверялась"),
        ]
        return s

    def _stage_11(self) -> Stage:
        s = Stage(11, "Функции подсистемы интеграции и импорта/экспорта данных", "4.1.1.7, 4.2.1.7",
                  "4.1.11")
        sc = self.scripted
        s.items += [
            sc(11, "а", "Импорт выписок CSV, OFX, QIF и сверка с исходными файлами"),
            sc(11, "б", "Экспорт истории операций за месяц в PDF и XLSX"),
            sc(11, "в", "Актуальность курсов валют и котировок"),
            sc(11, "г", "Использование сохраненных значений при недоступности источника",
               measured=lambda c: ru(clean_detail(c["detail"])) + "; для котировок "
                                   f"{M_TEST} test_saved_quote_is_used_when_source_is_unavailable"),
            sc(11, "д", "Подключение источника банковских данных без согласия невозможно"),
        ]
        return s

    def _stage_12(self) -> Stage:
        s = Stage(12, "Функции подсистемы администрирования и безопасности",
                  "4.1.1.8, 4.2.1.8, 4.4.4, 4.4.7", "4.1.12")
        sc = self.scripted

        def monitoring(c):
            m = c.get("monitoring") or {}
            return ru(f"доступность {m.get('availability_percent')} % по "
                      f"{m.get('health_checks')} проверкам; запросов {m.get('requests')}, среднее "
                      f"время ответа {m.get('average_response_ms')} мс, ошибок сервера "
                      f"{m.get('server_errors')}") if m else ru(c["detail"])

        s.items += [
            sc(12, "а", "Регистрация без согласия невозможна, подтверждение адреса по ссылке",
               measured=lambda c: ru(clean_detail(c["detail"])) + "; ссылка взята из журнала "
                                   "(почтовый сервер не подключен)"),
            sc(12, "б", "Восстановление пароля и двухфакторная аутентификация"),
            sc(12, "в", "Блокировка входа на 15 минут после пяти неудачных попыток",
               measured=lambda c: ru(re.sub(r"коды попыток: \[[^\]]*\]", "пять отказов с кодом 401",
                                            c["detail"]))
                                   + f"; длительность блокировки 15 мин {M_CODE} "
                                     "(параметр lockout_minutes)"),
            sc(12, "г", "Доступ к административным разделам ограничен ролями",
               measured=lambda c: ru(re.sub(r"коды ответов: \[([^\]]*)\]",
                                            r"запросы роли «Пользователь» к шести административным "
                                            r"разделам отклонены с кодами \1", c["detail"]))),
            sc(12, "д", "Создание, блокировка учетной записи, сброс пароля администратором"),
            sc(12, "е", "Журнал аудита: фильтр, экспорт и импорт CSV"),
            sc(12, "ж", "Раздел «Мониторинг»: доступность, время ответа, ошибки",
               "соответствует в части данных сервера; Prometheus и Grafana не запускались, "
               "отображение на устройстве не проверялось", measured=monitoring),
            sc(12, "з", "Обмен только по HTTPS, хеширование паролей, шифрование локальной БД",
               "соответствует в части хранения паролей; проверка обмена только по HTTPS (TLS на "
               "стенде не настроен) и шифрования локальной базы данных на устройстве не проводилась"),
        ]
        return s

    def _stage_13(self) -> Stage:
        s = Stage(13, "Эргономика пользовательского интерфейса", "4.4.5", "4.1.13")
        reason = "мобильное устройство отсутствовало"
        m = self.mobile or {}
        dark = [n for n in (m.get("screens") or []) if "dark" in str(n).lower()]
        s.items += [
            Item(13, "а", "Прохождение сценариев типовых пользователей", NOT_DONE, reason),
            Item(13, "б", "Отсутствие тупиков (переход к следующему шагу и возврат)", NOT_DONE,
                 reason),
            Item(13, "в", "Соответствие экранов п. 4.4.5 ТЗ по чек-листу", NOT_DONE,
                 "проверка по чек-листу не выполнялась"),
        ]
        if dark and self._mobile_ok():
            s.items.append(Item(13, "г", "Переключение цветовой темы", PARTIAL,
                                f"снимков экранов в темной теме: {len(dark)}",
                                "соответствует в части отображения экранов в темной теме на "
                                "снимках, построенных автоматизированными тестами; проверка всех "
                                "экранов на устройстве не проводилась"))
        else:
            s.items.append(Item(13, "г", "Переключение цветовой темы", NOT_DONE, reason))
        s.items += [
            Item(13, "д", "Озвучивание элементов программой TalkBack", NOT_DONE, reason),
            Item(13, "е", "Проверка приложением Accessibility Scanner", NOT_DONE, reason),
            Item(13, "ж", "Обозначение статусов не только цветом", NOT_DONE,
                 "проверка на устройстве не выполнялась"),
            Item(13, "з", "Критерии уровня A ГОСТ Р 52872-2019", NOT_DONE,
                 "проверка критериев не выполнялась"),
        ]
        return s

    # ------------------------------------------------------------------ временной регламент
    def group1(self) -> list[tuple[str, str, str, str, str | None]]:
        """Операции группы 1: (буква, операция, п. ТЗ, предел, серверная составляющая)."""
        add = self.detail_number(5, "а", r"время ответа ([\d.]+) с")
        saved = self.value(7, "в", "portfolio_seconds_saved")
        refresh = self.value(7, "в", "portfolio_seconds_refresh")
        sync = self.value(10, "б", "sync_seconds")
        imports = self.value(11, "а", "import_seconds") or {}
        exports = self.value(11, "б", "export_seconds") or {}
        port = self.request(self.level(self.main, 100), "GET /investments/portfolio")
        portfolio = None
        if saved is not None:
            portfolio = (f"по сохраненным котировкам {num(saved, 3)} с, с получением котировок "
                         f"{num(refresh, 3)} с")
            if port:
                portfolio += f"; под нагрузкой 100 пользователей 95-й перцентиль {num(port['p95_ms'], 0)} мс"
        io = None
        if imports:
            io = ("импорт 1 000 операций: " + ", ".join(
                f"{k.split('.')[-1].upper()} {num(v, 1)} с" for k, v in imports.items()))
            if exports:
                io += "; экспорт: " + ", ".join(f"{k.upper()} {num(v, 2)} с" for k, v in exports.items())
        return [
            ("1а", "Холодный запуск приложения до главного экрана", "4.2.2.1", "5 с", None),
            ("1б", "Добавление транзакции", "4.2.2.3", "30 с",
             f"сохранение на сервере {num(add, 3)} с" if add is not None else None),
            ("1в", "Распознавание суммы и категории по фотографии чека", "4.2.2.4", "15 с", None),
            ("1г", "Установка бюджетного лимита", "4.2.2.5", "10 с", None),
            ("1д", "Обновление прогресса цели после накопления", "4.2.2.6", "5 с", None),
            ("1е", "Расчет стоимости и доходности портфеля", "4.2.2.7", "3 с / 10 с", portfolio),
            ("1ж", "Синхронизация после восстановления сети", "4.2.2.11", "1 мин",
             f"обработка очереди сервером {num(sync, 3)} с" if sync is not None else None),
            ("1з", "Импорт выписки или экспорт отчета до 1 000 операций", "4.2.2.12", "30 с", io),
            ("1и", "Сохранение изменений профиля", "4.2.2.13", "5 с", None),
        ]

    def load_item(self, letter: str, title: str, names: list[str]) -> Item:
        """Пункт группы 2 по основному нагрузочному тесту (100 пользователей)."""
        level = self.level(self.main, 100)
        found = [(n, self.request(level, n)) for n in names]
        found = [(n, r) for n, r in found if r]
        if not found:
            return Item(14, letter, title, NOT_DONE, "результаты нагрузочного тестирования отсутствуют")
        parts, ok = [], True
        for name, r in found:
            good = r["p95_ms"] <= r["limit_ms"] and r["within_limit_percent"] >= 95
            ok &= good
            parts.append(f"{REQUEST_TITLES.get(name, name).lower()}: 95-й перцентиль "
                         f"{num(r['p95_ms'], 0)} мс при пределе {num(r['limit_ms'], 0)} мс, в пределах "
                         f"{pct(r['within_limit_percent'], 2)}")
        return Item(14, letter, title, FULL if ok else FAIL, "; ".join(parts) + " (100 пользователей)")

    def _stage_14(self) -> Stage:
        s = Stage(14, "Временной регламент реализации функций", "4.2.2", "4.1.14")
        for letter, title, point, limit, server in self.group1():
            name = f"{title} – не более {limit} (п. {point} ТЗ)"
            if server:
                s.items.append(Item(14, letter, name, PARTIAL, server,
                                    "соответствует в части серверной составляющей; замеры на "
                                    "устройстве не проводились"))
            else:
                s.items.append(Item(14, letter, name, NOT_DONE,
                                    "серия замеров на устройстве не проводилась"))
        s.items.append(self.load_item("2а", "Основные запросы – не более 2 с для 95 % (п. 4.2.2.2 ТЗ)",
                                      [BASIC]))
        s.items.append(self.load_item("2б", "Аналитический отчет – не более 5 с для 95 % "
                                            "(п. 4.2.2.8 ТЗ)", ["GET /reports"]))
        s.items.append(self.load_item("2в", "Рекомендации и прогноз – не более 10 с для 95 % "
                                            "(п. 4.2.2.9 ТЗ)",
                                      ["GET /recommendations", "GET /forecast"]))
        push = (self.monitoring or {}).get("push")
        title = "Передача push-уведомления – не позднее 1 мин для 95 % событий (п. 4.2.2.10 ТЗ)"
        if push and push.get("handed_over"):
            total = push["handed_over"] + push["not_handed_over"]
            text = (f"передано {push['handed_over']} из {total} уведомлений, из переданных в пределах "
                    f"1 мин – {pct(push['within_minute_percent'])}, 95-й перцентиль "
                    f"{num(push['p95_seconds'], 1)} с, наибольшее {num(push['max_seconds'], 1)} с")
            if push["within_minute_percent"] < 95:
                s.items.append(Item(14, "2г", title, FAIL, text))
            else:
                part = ("соответствует для переданных уведомлений (передача в журнал вместо сервиса "
                        "доставки FCM)")
                if push["not_handed_over"]:
                    part += (f"; для {push['not_handed_over']} уведомлений передача не "
                             "зафиксирована, причина не установлена")
                s.items.append(Item(14, "2г", title, PARTIAL, text, part))
        else:
            s.items.append(Item(14, "2г", title, NOT_DONE, "данные мониторинга отсутствуют"))
        logs = "проверка по журналам за весь период испытаний не проводилась"
        s.items.append(Item(14, "3а", "Предупреждение о финансовом риске – не позднее 1 сут. "
                                      "(п. 4.2.2.14 ТЗ)", PARTIAL,
                            "предупреждение формируется при запросе (этап 9, п. г) и ежесуточной "
                            f"задачей анализа данных пользователей ({M_CODE})",
                            "соответствует по анализу исходного текста; " + logs))
        s.items.append(Item(14, "3б", "Обновление рыночных цен – не реже раза в сутки и при открытии "
                                      "портфеля (п. 4.2.2.15 ТЗ)", PARTIAL,
                            "обновление при открытии портфеля подтверждено на этапе 7; ежесуточное "
                            f"обновление выполняется задачей daily ({M_CODE})",
                            "соответствует в части обновления при открытии портфеля; " + logs))
        retrain = self.value(9, "е", "retrain_seconds")
        training = (self.main or {}).get("retraining") if self.main else None
        if retrain is not None:
            text = f"обучение по запуску аналитика – {num(retrain, 1)} с"
            if training and training.get("trainings"):
                text += (f"; во время основного нагрузочного теста – обучений: {training['trainings']}, "
                         f"общая длительность {num(training['training_seconds_total'], 1)} с")
            s.items.append(Item(14, "3в", "Повторное обучение модели – не более 2 ч (п. 4.2.2.16 ТЗ)",
                                FULL if retrain <= 7200 else FAIL, text))
        else:
            s.items.append(Item(14, "3в", "Повторное обучение модели – не более 2 ч (п. 4.2.2.16 ТЗ)",
                                NOT_DONE, "результат отсутствует"))
        return s

    # ------------------------------------------------------------------ одновременность
    def basic_ok(self, level: dict | None) -> bool:
        basic, every = self.request(level, BASIC), self.request(level, ALL)
        return bool(basic and every and basic["p95_ms"] <= 2000 and every["errors_percent"] <= 1)

    def _stage_15(self) -> Stage:
        s = Stage(15, "Одновременность выполнения группы функций", "4.2.3", "4.1.15")
        # а) ступенчатая нагрузка
        levels = {u: self.level(self.steps, u) for u in (50, 100, 200)}
        if all(levels.values()):
            parts, ok, short = [], True, []
            for users, level in levels.items():
                basic, every = self.request(level, BASIC), self.request(level, ALL)
                parts.append(f"{users} польз.: 95-й перцентиль основных запросов "
                             f"{num(basic['p95_ms'], 0)} мс, ошибок {pct(every['errors_percent'], 2)}")
                ok &= every["errors_percent"] <= 1 and (users == 200 or basic["p95_ms"] <= 2000)
                if level["minutes"] < 9:
                    short.append(f"{users} польз. – {num(level['minutes'], 1)} мин")
            text = "; ".join(parts)
            if not ok:
                s.items.append(Item(15, "а", "Ступенчатая нагрузка 50, 100 и 200 пользователей",
                                    FAIL, text))
            elif short:
                s.items.append(Item(15, "а", "Ступенчатая нагрузка 50, 100 и 200 пользователей",
                                    PARTIAL, text, "соответствует по измеренным значениям; "
                                    "продолжительность ступеней меньше 10 мин: " + ", ".join(short)))
            else:
                s.items.append(Item(15, "а", "Ступенчатая нагрузка 50, 100 и 200 пользователей",
                                    FULL, text))
        else:
            s.items.append(Item(15, "а", "Ступенчатая нагрузка 50, 100 и 200 пользователей",
                                NOT_DONE, "результаты отсутствуют"))
        # б) основной тест
        level = self.level(self.main, 100)
        title = "Основной тест: 100 пользователей не менее 30 мин, не менее 18 000 запросов"
        if level:
            basic, every = self.request(level, BASIC), self.request(level, ALL)
            text = (f"запросов: {num(every['requests'], 0)} за {num(level['minutes'], 1)} мин; 95-й "
                    f"перцентиль основных запросов {num(basic['p95_ms'], 0)} мс, ошибок "
                    f"{pct(every['errors_percent'], 2)}")
            if not self.basic_ok(level):
                s.items.append(Item(15, "б", title, FAIL, text))
            elif every["requests"] < 18000 or level["minutes"] < 29.5:
                s.items.append(Item(15, "б", title, PARTIAL, text,
                                    "соответствует по измеренным значениям; объем теста меньше "
                                    "установленного ПМИ"))
            else:
                s.items.append(Item(15, "б", title, FULL, text))
        else:
            s.items.append(Item(15, "б", title, NOT_DONE, "результаты отсутствуют"))
        # в) второй экземпляр
        two, one = self.level(self.two, 200), self.level(self.steps, 200)
        title = "200 пользователей: второй экземпляр серверного приложения"
        if two:
            b2 = self.request(two, BASIC)
            text = f"95-й перцентиль основных запросов {num(b2['p95_ms'], 0)} мс"
            b1 = self.request(one, BASIC)
            if b1:
                text += f" (на одном экземпляре – {num(b1['p95_ms'], 0)} мс)"
            text += f", ошибок {pct(self.request(two, ALL)['errors_percent'], 2)}"
            if self.basic_ok(two):
                s.items.append(Item(15, "в", title, PARTIAL, text,
                                    "соответствует для двух экземпляров на одной виртуальной "
                                    "машине; масштабирование на отдельные серверы не проверялось"))
            else:
                s.items.append(Item(15, "в", title, FAIL, text))
        else:
            s.items.append(Item(15, "в", title, NOT_DONE, "результаты отсутствуют"))
        # г) повторное обучение
        r = (self.main or {}).get("retraining")
        title = "Повторное обучение модели: рост 95-го перцентиля не более 10 %"
        if r and r.get("trainings") and r.get("p95_change_percent") is not None:
            text = (f"95-й перцентиль основных запросов без обучения {num(r['p95_without_training_ms'], 0)} "
                    f"мс, во время обучения {num(r['p95_during_training_ms'], 0)} мс; изменение "
                    f"{num(r['p95_change_percent'], 1)} % (запросов во время обучения: "
                    f"{num(r['requests_during_training'], 0)})")
            s.items.append(Item(15, "г", title, FULL if r["p95_change_percent"] <= 10 else FAIL, text))
        else:
            s.items.append(Item(15, "г", title, NOT_DONE, "результаты отсутствуют"))
        s.items.append(Item(15, "д", "Синхронизация большого объема данных одновременно с вводом "
                                     "транзакции", NOT_DONE, "мобильное устройство отсутствовало"))
        s.items.append(self.scripted(15, "е", "Два администратора одновременно изменяют одну учетную "
                                              "запись",
                                     measured=lambda c: ru(clean_detail(c["detail"]))
                                     + " (изменялись разные поля учетной записи)"))
        return s

    def _stage_16(self) -> Stage:
        s = Stage(16, "Совместимость с мобильными устройствами", "4.3.4.1, 4.3.5.1", "4.1.16")
        m = self.mobile or {}
        sdk = (f"; в сборке minSdk {m['min_sdk']} (Android 8.0 – API 26 по п. 4.3.4.1 ТЗ)"
               if m.get("min_sdk") and not m.get("placeholder") else "")
        reason = "тестовые смартфоны и эмуляторы отсутствовали"
        s.items += [
            Item(16, "а", "Установка тестовой сборки на пять смартфонов и эмуляторы", NOT_DONE,
                 reason + sdk),
            Item(16, "б", "Основные сценарии пользователя на каждом устройстве", NOT_DONE, reason),
            Item(16, "в", "Отображение при разных размерах экрана, в светлой и темной темах",
                 NOT_DONE, reason),
        ]
        return s

    # ------------------------------------------------------------------ итоги
    def totals(self) -> dict[str, int]:
        items = [i for s in self.stages for i in s.items]
        return {"all": len(items), **{v: sum(1 for i in items if i.verdict == v)
                                      for v in (FULL, PARTIAL, FAIL, NOT_DONE)}}

    def stage(self, number: int) -> Stage:
        return next(s for s in self.stages if s.number == number)

    # ------------------------------------------------------------------ доработки
    def _build_improvements(self) -> list[Improvement]:
        out: list[Improvement] = []
        add = lambda text, deadline: out.append(Improvement(text, deadline))  # noqa: E731
        # Несоответствия, выявленные сценариями
        for stage in self.stages:
            for item in stage.items:
                if item.verdict == FAIL and stage.number not in (2, 3):
                    add(f"Устранить несоответствие по этапу {stage.number}, п. {item.letter} "
                        f"(«{item.title}») и повторить проверку", D_PRACTICE)
        add("Провести проверки на пяти смартфонах и эмуляторах по п. 4.1.16 ПМИ, включая "
            "операции, требующие устройства: сохранность данных при аварийном завершении и "
            "отключении сети (этап 4), ввод на устройстве и отображение экранов (этапы 5–12), "
            "автономный режим и конфликт двух устройств (этап 10), синхронизацию одновременно с "
            "вводом (этап 15)", D_FEB)
        add("Выполнить серии замеров временных характеристик операций группы 1 (не менее 1 000 "
            "повторений, Jetpack Macrobenchmark и UI Automator) на устройстве с наименьшими "
            "характеристиками и рассчитать доверительные интервалы по п. 4.1.14 ПМИ", D_FEB)
        add("Проверить эргономику и доступность по п. 4.1.13 ПМИ: сценарии типовых пользователей, "
            "чек-лист п. 4.4.5 ТЗ, программа TalkBack, приложение Accessibility Scanner, критерии "
            "уровня A ГОСТ Р 52872-2019", D_FEB)
        add("Проверить на устройстве распознавание фотографии чека (ML Kit) и голосовой ввод, в том "
            "числе время распознавания не более 15 с", D_FEB)
        add("Установить на стенде сертификат TLS, запретить обмен по HTTP, проверить анализатором "
            "трафика обмен только по HTTPS (TLS не ниже 1.2) и шифрование локальной базы данных "
            "на устройстве", D_JAN)
        add("Подключить Firebase Cloud Messaging и почтовый сервер, проверить доставку "
            "push-уведомлений и писем на устройство и в почтовый ящик", D_JAN)
        add("Обеспечить распознавание кириллического текста на фотографии чека (наименование "
            "торговой точки): включенная в приложение модель ML Kit поддерживает только латиницу", D_FEB)
        add("Реализовать в мобильном приложении управление согласиями на получение банковских "
            "данных (п. 4.2.1.7 д ТЗ), изменение роли учетной записи, отклонение рекомендации, "
            "импорт и экспорт версий моделей", D_FEB)
        push = (self.monitoring or {}).get("push") or {}
        if push.get("not_handed_over"):
            add(f"Установить причину отсутствия передачи {push['not_handed_over']} уведомлений в "
                "сервис доставки по данным мониторинга и устранить ее", D_PRACTICE)
        add("Настроить репликацию базы данных с автоматическим переключением на резервный узел и "
            "проверить переключение без потери данных (п. 4.1.4 б ПМИ)", D_FEB)
        add("Развернуть стенд в контейнерах Docker Compose с S3-совместимым объектным хранилищем, "
            "Prometheus и Grafana и повторить сценарии испытаний серверной части", D_JAN)
        add("Обеспечить непрерывный мониторинг доступности серверной части в течение всего периода "
            "повторных испытаний и проверить по журналам формирование предупреждений о рисках и "
            "ежесуточное обновление цен (п. 4.1.4 д, 4.1.14 г ПМИ)", D_FEB)
        if self.stage(15).items[2].verdict in (PARTIAL, NOT_DONE):
            add("Проверить горизонтальное масштабирование с размещением экземпляров серверного "
                "приложения на отдельных серверах", D_FEB)
        if self.stage(8).items[3].verdict == PARTIAL:
            add("Повторить проверку фильтра отчета по счету на счете, имеющем расходы за период",
                D_PRACTICE)
        main_level = self.level(self.main, 100)
        login = self.request(main_level, LOGIN)
        if login and login["p95_ms"] > login["limit_ms"]:
            add(f"Сократить время входа в систему: 95-й перцентиль {num(login['p95_ms'], 0)} мс при "
                "100 пользователях превышает 2 с", D_JAN)
        if self.stage(15).items[1].verdict == PARTIAL or self.stage(15).items[0].verdict == PARTIAL:
            add("Повторить нагрузочные тесты в объеме, установленном п. 4.1.15 ПМИ", D_JAN)
        unseen = (self.model or {}).get("unseen_accuracy")
        add("Расширить обучающую выборку модели категоризации примерами, накопленными пользователями"
            + (f": точность на торговых точках, отсутствующих в обучающей выборке, составляет "
               f"{pct(unseen * 100)}" if unseen is not None else ""), D_TRIAL)
        add("Оценить долю положительных оценок рекомендаций на представительной выборке оценок "
            "пользователей", D_TRIAL)
        add("Разработать недостающие документы по п. 6.3 ТЗ (пояснительная записка к техническому "
            "проекту, общее описание системы и описание программного обеспечения, программы и "
            "методики опытной эксплуатации и приемочных испытаний, акт завершения работ стадии) и "
            "оформить документацию по ГОСТ Р 59795-2021 в соответствии с п. 9.2 ТЗ", D_JAN)
        return out


def clean_detail(text: str) -> str:
    """Удаляет из описания результата оговорки о непроверенном (они отражены в оценке)."""
    text = re.sub(r"(?<=\.)\s*[А-ЯЁ][^.;]*не (?:проверял|выполнял)[^.;]*(?:\.|$)", "", text)
    return text.strip().rstrip(";").strip()


def load(strict: bool = False) -> Results:
    results = Results(strict=strict)
    if results.is_sample:
        print("ПРЕДУПРЕЖДЕНИЕ: результаты испытаний содержат пробные данные: "
              f"{', '.join(results.samples) or '–'}; отсутствуют: {', '.join(results.missing) or '–'}",
              file=sys.stderr)
    return results
