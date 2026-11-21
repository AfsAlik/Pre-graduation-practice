"""Диаграммы отчета: ArchiMate, SysML и UML.

Запуск:  python diagrams.py <каталог для SVG>
Затем:   node render.js <каталог с SVG> <каталог для PNG>
"""
import os
import sys

from diagram_kit import APPLICATION, BUSINESS, TECHNOLOGY, WHITE, Box, Diagram


# ---------------------------------------------------------------------------
def use_cases() -> Diagram:
    """UML: диаграмма вариантов использования."""
    d = Diagram(1180, 900)
    d.frame(250, 30, 690, 850, "Финансовый помощник")
    user = d.actor(110, 250, ["Пользователь"])
    analyst = d.actor(110, 600, ["Аналитик"])
    admin = d.actor(110, 770, ["Администратор"])
    bank = d.actor(1060, 150, ["Банк России", "(курсы валют)"])
    push = d.actor(1060, 290, ["Сервис доставки", "push-уведомлений"])
    mail = d.actor(1060, 440, ["Почтовый", "сервер"])
    moex = d.actor(1060, 600, ["Московская биржа", "(котировки)"])

    w, h = 250, 44
    left, right = 410, 770
    uc = {}
    rows_left = [
        ("auth", ["Зарегистрироваться", "и войти в систему"]),
        ("tx", ["Добавить транзакцию"]),
        ("accounts", ["Вести счета и категории"]),
        ("budget", ["Установить бюджетный лимит", "и контролировать его"]),
        ("goal", ["Поставить финансовую цель", "и вносить накопления"]),
        ("invest", ["Вести инвестиционный", "портфель"]),
        ("report", ["Просматривать отчеты", "и главный экран"]),
        ("recs", ["Получать рекомендации,", "прогнозы и предупреждения"]),
        ("io", ["Импортировать выписку,", "экспортировать отчет"]),
        ("offline", ["Работать без подключения", "к сети Интернет"]),
    ]
    y = 76
    for key, lines in rows_left:
        uc[key] = d.usecase(left, y, w, h, lines)
        y += 58
    uc["quality"] = d.usecase(left, 668, w, h, ["Контролировать качество", "моделей и рекомендаций"])
    uc["train"] = d.usecase(left, 726, w, h, ["Запустить повторное", "обучение модели"])
    uc["users"] = d.usecase(left, 790, w, h, ["Управлять учетными", "записями"])
    uc["logs"] = d.usecase(left, 848, w, h, ["Просматривать журналы", "и мониторинг"])

    uc["quick"] = d.usecase(right, 76, 270, h, ["Быстрый ввод: шаблон,", "фото чека, голос"])
    uc["cat"] = d.usecase(right, 140, 270, h, ["Определить категорию", "автоматически"])
    uc["rates"] = d.usecase(right, 212, 270, h, ["Пересчитать сумму", "по курсу валют"])
    uc["notify"] = d.usecase(right, 300, 270, h, ["Уведомить о лимите,", "цели, платеже, риске"])
    uc["quotes"] = d.usecase(right, 400, 270, h, ["Получить рыночные цены", "и рассчитать доходность"])
    uc["sync"] = d.usecase(right, 598, 270, h, ["Синхронизировать данные", "с сервером"])
    uc["audit"] = d.usecase(right, 800, 270, h, ["Записать действие", "в журнал аудита"])

    for key, _ in rows_left:
        d.edge([user.right(0.45), uc[key].left()], "assoc")
    for key in ("quality", "train"):
        d.edge([analyst.right(0.5), uc[key].left()], "assoc")
    for key in ("users", "logs"):
        d.edge([admin.right(0.5), uc[key].left()], "assoc")
    # Аналитик и администратор – специализации пользователя
    d.edge([(analyst.cx, analyst.y - 4), (user.cx, user.y + 100)], "gen")
    d.edge([(admin.cx, admin.y - 4), (analyst.cx, analyst.y + 100)], "gen")

    def rel(a, b, text, at=(0.5, 0.5), sides=("right", "left"), **kw):
        d.link(uc[a], uc[b], "dep", text, sides=sides, at=at, route="straight", size=10, **kw)

    rel("quick", "tx", "«extend»", sides=("left", "right"), at=(0.5, 0.3))
    rel("tx", "cat", "«include»", at=(0.7, 0.5))
    rel("accounts", "rates", "«include»")
    rel("budget", "notify", "«include»", at=(0.5, 0.3))
    rel("goal", "notify", "«include»", at=(0.5, 0.7))
    rel("invest", "quotes", "«include»")
    rel("offline", "sync", "«include»")
    rel("users", "audit", "«include»")

    d.edge([uc["rates"].right(), bank.left(0.5)], "assoc")
    d.edge([uc["notify"].right(0.4), push.left(0.5)], "assoc")
    d.edge([uc["notify"].right(0.7), mail.left(0.5)], "assoc")
    d.edge([uc["quotes"].right(), moex.left(0.5)], "assoc")
    return d


# ---------------------------------------------------------------------------
def deployment() -> Diagram:
    """UML: диаграмма развертывания."""
    d = Diagram(1180, 760)
    phone = d.node3d(40, 60, 300, 330, ["«device»", "Смартфон пользователя", "Android 8.0 (API 26) и выше"])
    app = d.component(65, 135, 250, 56, ["«artifact»", "finhelper.apk"], fill=WHITE)
    room = d.cylinder(65, 215, 250, 70, ["Локальная БД", "Room + SQLCipher (AES-256)"], size=11)
    keystore = d.node(65, 305, 250, 60, ["Android Keystore", "(ключ БД, токены доступа)"], size=11,
                      bold=False)
    del app, room, keystore

    server = d.node3d(430, 60, 420, 440, ["«execution environment»", "Сервер приложений",
                                           "Ubuntu Server 22.04, Docker Compose"])
    proxy = d.component(455, 138, 370, 46, ["Обратный прокси (nginx), сертификат TLS"], size=11)
    api = d.component(455, 206, 175, 80, ["«container» api", "Серверное приложение", "FastAPI, gunicorn"],
                      size=11)
    worker = d.component(650, 206, 175, 80, ["«container» worker", "Фоновые задачи", "и планировщик"],
                         size=11)
    ml = d.component(455, 308, 175, 80, ["«container» ml", "Сервис машинного", "обучения"], size=11)
    redis = d.component(650, 308, 175, 80, ["«container» redis", "Кеш и очередь", "задач (Redis 7)"],
                        size=11)
    files = d.node(455, 410, 175, 66, ["Том данных:", "модели, файлы чеков"], size=11, bold=False)
    d.component(650, 410, 175, 66, ["Prometheus, Grafana", "(сбор метрик /metrics)"], size=11)

    dbnode = d.node3d(430, 560, 420, 150, ["«execution environment»", "Сервер базы данных"])
    db = d.cylinder(470, 615, 160, 76, ["PostgreSQL 16", "основной узел"], size=11)
    replica = d.cylinder(670, 615, 160, 76, ["PostgreSQL 16", "резервный узел"], size=11)

    ext = d.frame(930, 60, 220, 440, "Внешние сервисы", dash=True)
    cbr = d.node(950, 110, 180, 60, ["Банк России", "курсы валют (HTTPS)"], size=11)
    moex = d.node(950, 195, 180, 60, ["Московская биржа", "ISS MOEX (HTTPS)"], size=11)
    fcm = d.node(950, 280, 180, 60, ["Firebase Cloud", "Messaging"], size=11)
    smtp = d.node(950, 365, 180, 60, ["Почтовый сервер", "(SMTP)"], size=11)
    s3 = d.node(950, 590, 180, 70, ["S3-совместимое", "хранилище", "(резервные копии)"], size=11,
                bold=False)
    del ext

    d.edge([phone.right(0.31), proxy.left()], "dir", "REST API, JSON\nHTTPS (TLS 1.2+)", label_dy=-18)
    d.link(proxy, api, "dir", sides=("bottom", "top"), at=(0.25, 0.5), route="straight")
    d.link(api, ml, "dir", "HTTP", sides=("bottom", "top"), route="straight", label_dx=22, label_dy=4)
    d.edge([api.bottom(0.85), (api.x + api.w * 0.85, 297), (redis.x + 30, 297), redis.top(0.17)], "dir")
    d.link(worker, redis, "dir", sides=("bottom", "top"), route="straight")
    d.link(ml, files, "dir", sides=("bottom", "top"), route="straight")
    d.edge([api.left(0.7), (442, 262), (442, 540), (550, 540), db.top()], "dir", "SQL", label_dx=-14)
    d.link(db, replica, "dep", "репликация", sides=("right", "left"), route="straight")
    d.edge([worker.right(0.3), cbr.left()], "dir")
    d.edge([worker.right(0.5), moex.left()], "dir")
    d.edge([worker.right(0.7), fcm.left()], "dir")
    d.edge([worker.right(0.9), (900, 278), (900, 395), smtp.left()], "dir")
    d.edge([replica.right(), s3.left(0.6)], "dep", "pg_dump")
    del server, dbnode
    return d


# ---------------------------------------------------------------------------
def archimate() -> Diagram:
    """ArchiMate: многослойное представление архитектуры."""
    d = Diagram(1180, 990)
    d.frame(20, 20, 1140, 250, "Бизнес-слой", fill="#FFFCE0", tab=False)
    d.frame(20, 290, 1140, 380, "Слой приложений", fill="#EAF7FD", tab=False)
    d.frame(20, 690, 1140, 280, "Технологический слой", fill="#ECF7E8", tab=False)

    # --- бизнес-слой
    user = d.archi(250, 50, 200, 50, ["Пользователь"], BUSINESS, "actor")
    analyst = d.archi(770, 50, 170, 50, ["Аналитик данных"], BUSINESS, "actor")
    admin = d.archi(960, 50, 180, 50, ["Администратор"], BUSINESS, "actor")
    names = [["Учет доходов", "и расходов"], ["Планирование", "бюджета и целей"],
             ["Учет", "инвестиций"], ["Анализ финансов,", "рекомендации"],
             ["Контроль качества", "моделей"], ["Администрирование", "системы"]]
    processes = []
    for index, lines in enumerate(names):
        processes.append(d.archi(40 + index * 185, 160, 172, 62, lines, BUSINESS, "process",
                                 rounded=True, size=11.5))
    for process in processes[:4]:
        d.link(user, process, "assign", sides=("bottom", "top"), at=(0.5, 0.5), route="z", mid=130)
    d.link(analyst, processes[4], "assign", sides=("bottom", "top"), route="z", mid=130)
    d.link(admin, processes[5], "assign", sides=("bottom", "top"), route="z", mid=130)

    # --- слой приложений
    service_names = [["Учет операций", "и счетов"], ["Бюджетирование", "и цели"],
                     ["Инвестиционный", "учет"], ["Отчеты, прогнозы,", "рекомендации"],
                     ["Управление", "моделями ML"], ["Учетные записи,", "журналы, мониторинг"]]
    services = []
    for index, lines in enumerate(service_names):
        services.append(d.archi(40 + index * 185, 320, 172, 62, lines, APPLICATION, "service",
                                rounded=True, size=11.5))
    for service, process in zip(services, processes, strict=True):
        d.link(service, process, "serve", sides=("top", "bottom"), route="straight")
    mobile = d.archi(40, 440, 1097, 60,
                     ["Мобильное приложение «Финансовый помощник» (Android, Kotlin, Jetpack Compose):",
                      "экраны пользователя и административный модуль, локальная БД, модуль синхронизации"],
                     APPLICATION, "component")
    for service in services:
        d.link(mobile, service, "real", sides=("top", "bottom"),
               at=((service.cx - mobile.x) / mobile.w, 0.5), route="straight")
    server = d.archi(300, 570, 260, 70, ["Серверное приложение", "(REST API, 8 подсистем)"],
                     APPLICATION, "component")
    worker = d.archi(590, 570, 260, 70, ["Исполнитель фоновых задач", "и планировщик"],
                     APPLICATION, "component")
    ml = d.archi(880, 570, 257, 70, ["Сервис машинного обучения", "(категоризация, прогноз)"],
                 APPLICATION, "component")
    d.link(server, mobile, "serve", "REST API, JSON, HTTPS", sides=("top", "bottom"),
           at=(0.5, (server.cx - mobile.x) / mobile.w), route="straight", label_dx=-86, label_dy=4)
    d.link(worker, server, "serve", sides=("left", "right"), route="straight")
    d.edge([ml.top(0.5), (ml.cx, 540), (server.x + 200, 540), server.top(0.77)], "serve")

    # --- технологический слой
    phone = d.archi(40, 720, 230, 70, ["Смартфон", "(Android 8.0 и выше)"], TECHNOLOGY, "node")
    host = d.archi(300, 720, 837, 70,
                   ["Сервер приложений: Ubuntu Server, Docker (контейнеры api, worker, ml)"],
                   TECHNOLOGY, "node")
    d.edge([phone.top(0.5), (phone.cx, mobile.y + mobile.h)], "real")
    for component in (server, worker, ml):
        d.link(host, component, "real", sides=("top", "bottom"),
               at=((component.cx - host.x) / host.w, 0.5), route="straight")
    keystore = d.archi(40, 850, 230, 90, ["Android Keystore,", "SQLite + SQLCipher"], TECHNOLOGY,
                       "software", size=11.5)
    d.link(keystore, phone, "serve", sides=("top", "bottom"), route="straight")
    tech = [["PostgreSQL 16", "(основной и", "резервный узлы)"], ["Redis 7", "(кеш, очередь", "задач)"],
            ["S3-хранилище", "(файлы, копии)"], ["Prometheus,", "Grafana"],
            ["Банк России", "(курсы валют)"], ["Московская", "биржа", "(котировки)"],
            ["Firebase Cloud", "Messaging,", "SMTP-сервер"]]
    width = (837 - 6 * 9) / 7
    for index, lines in enumerate(tech):
        box = d.archi(300 + index * (width + 9), 850, width, 90, lines, TECHNOLOGY,
                      "software" if index < 4 else "interface", size=10.5)
        d.link(box, host, "serve", sides=("top", "bottom"),
               at=(0.5, (box.cx - host.x) / host.w), route="straight")
    return d


# ---------------------------------------------------------------------------
def sysml_bdd() -> Diagram:
    """SysML: диаграмма определения блоков."""
    d = Diagram(1180, 760)
    d.frame(15, 15, 1150, 730, "bdd [Package] Финансовый помощник [Состав системы]")
    system = d.cls(410, 60, 360, "Финансовый помощник", stereotype="system", fill="#F7F7F7", attrs=[
        "values", "  одновременных пользователей ≥ 100", "  доступность серверной части ≥ 99 %",
        "  исторические данные: не менее 5 лет"])
    mobile = d.cls(35, 250, 265, "Мобильное приложение", stereotype="block", attrs=[
        "parts", "  ui : Интерфейс (Jetpack Compose)", "  db : Локальная БД (Room, SQLCipher)",
        "  sync : Модуль синхронизации", "  ocr : Распознавание чеков (ML Kit)",
        "  voice : Голосовой ввод", "values", "  ОС: Android 8.0 (API 26) и выше",
        "  холодный запуск ≤ 5 с"], ops=["добавитьТранзакцию()", "синхронизировать()",
                                         "показатьУведомление()"])
    server = d.cls(325, 250, 280, "Серверное приложение", stereotype="block", attrs=[
        "parts", "  учет транзакций и счетов", "  бюджетирование и цели", "  инвестиционный учет",
        "  аналитика и отчетность", "  интеллектуальные рекомендации",
        "  уведомления и синхронизация", "  интеграция, импорт и экспорт",
        "  администрирование и безопасность", "values", "  отклик ≤ 2 с для 95 % запросов"],
        ops=["REST API /api/v1/*", "POST /sync"])
    ml = d.cls(630, 250, 255, "Сервис машинного обучения", stereotype="block", attrs=[
        "values", "  точность категоризации ≥ 80 %", "  переобучение ≤ 2 ч"],
        ops=["train(samples, version)", "categorize(items)", "evaluate(samples)",
             "forecast(values)"])
    storage = d.cls(910, 250, 240, "Хранилище данных", stereotype="block", attrs=[
        "parts", "  PostgreSQL (основной узел)", "  PostgreSQL (резервный узел)",
        "  Redis (кеш, очередь задач)", "  файловое хранилище (S3)", "values",
        "  резервные копии: 3 месяца"])
    for index, block in enumerate((mobile, server, ml, storage)):
        start = system.bottom(0.2 + 0.2 * index)
        d.edge([start, (start[0], 215), (block.cx, 215), block.top()], "comp")
    for first, second in ((mobile, server), (server, ml), (ml, storage)):
        d.edge([(first.x + first.w, 282), (second.x, 282)], "assoc")

    actors = [("Пользователь", 35, 92), ("Аналитик", 137, 76), ("Администратор", 223, 104)]
    for name, x, width in actors:
        box = d.cls(x, 600, width, name, stereotype="actor", size=10)
        d.edge([box.top(), (box.cx, mobile.y + mobile.h)], "assoc")
    externals = [("Источник курсов валют", ["Банк России", "формат: XML"]),
                 ("Источник котировок", ["Московская биржа", "формат: JSON"]),
                 ("Сервис push-уведомлений", ["Firebase Cloud Messaging"]),
                 ("Почтовый сервер", ["протокол SMTP"])]
    x = 345
    for name, attrs in externals:
        box = d.cls(x, 650, 195, name, stereotype="external", attrs=["  " + a for a in attrs],
                    fill="#F7F7F7", size=10.5)
        d.edge([box.top(), (box.cx, 615), (server.x + server.w * 0.5, 615),
                (server.x + server.w * 0.5, server.y + server.h)], "dir")
        x += 205
    d.text(server.x + server.w * 0.5 + 8, 608, "использует", 10.5, anchor="start", style="italic")
    return d


# ---------------------------------------------------------------------------
def sysml_ibd() -> Diagram:
    """SysML: диаграмма внутренних блоков."""
    d = Diagram(1180, 720)
    d.frame(15, 15, 1150, 690, "ibd [Block] Финансовый помощник [Взаимодействие частей]")
    user = d.node(30, 150, 110, 70, ["«actor»", "Пользователь"], size=11.5)
    mobile = d.node(280, 80, 240, 230, [": Мобильное приложение"], size=13)
    server = d.node(660, 80, 240, 230, [": Серверное приложение"], size=13)
    ml = d.node(660, 420, 240, 110, [": Сервис машинного", "обучения"], size=13)
    pg = d.node(980, 70, 165, 70, ["«part»", "PostgreSQL"], size=12)
    redis = d.node(980, 160, 165, 70, ["«part»", "Redis"], size=12)
    files = d.node(980, 250, 165, 70, ["«part»", "Файловое", "хранилище"], size=12)
    worker = d.node(980, 420, 165, 110, [": Исполнитель", "фоновых задач"], size=12.5)
    rates = d.node(40, 420, 190, 56, ["«external»", "Источник курсов валют"], size=11.5, dash=True)
    quotes = d.node(40, 496, 190, 56, ["«external»", "Источник котировок"], size=11.5, dash=True)
    push = d.node(40, 590, 190, 56, ["«external»", "Сервис push-уведомлений"], size=11, dash=True)
    mail = d.node(300, 590, 190, 56, ["«external»", "Почтовый сервер"], size=11.5, dash=True)

    def flow(points, label, **kw):
        d.edge(points, "dir", label, **kw)
        d.port(points[0])
        d.port(points[-1])

    flow([(user.x + user.w, 170), (mobile.x, 170)], "касания, фото чека,\nречь", size=10, label_dy=-20)
    flow([(mobile.x, 204), (user.x + user.w, 204)], "экраны,\nуведомления", size=10, label_dy=16)
    flow([mobile.right(0.3), server.left(0.3)], "операции автономного\nрежима, запросы", size=10,
         label_dy=-20)
    flow([server.left(0.7), mobile.right(0.7)], "изменения, отчеты,\nрекомендации (JSON)", size=10,
         label_dy=-20)
    d.text(590, 200, "REST API, HTTPS", 10.5, weight="bold")
    flow([server.bottom(0.3), ml.top(0.3)], "обезличенные тексты\nопераций, ряды", size=10,
         label_dx=-72, label_dy=-2)
    flow([ml.top(0.7), server.bottom(0.7)], "категории, прогнозы,\nпоказатели качества", size=10,
         label_dx=76, label_dy=-2)
    flow([(server.x + server.w, pg.cy), pg.left()], "SQL", size=10)
    flow([(server.x + server.w, redis.cy), redis.left()], "задачи, кеш", size=10)
    flow([(server.x + server.w, files.cy), files.left()], "файлы", size=10)
    flow([redis.right(0.5), (1160, redis.cy), (1160, worker.cy), worker.right(0.5)], "задачи", size=10,
         label_at=0.68, label_dx=-24)
    flow([worker.left(0.3), ml.right(0.3)], "обучение", size=10)
    d.edge([worker.bottom(0.5), (worker.cx, 560), (255, 560), (255, rates.cy), rates.right(0.5)], "dep")
    d.edge([(255, quotes.cy), quotes.right(0.5)], "dep")
    d.text(620, 553, "запросы курсов валют и котировок (HTTPS)", 10)
    d.edge([worker.bottom(0.75), (worker.x + worker.w * 0.75, mail.cy), mail.right(0.5)], "dir",
           "уведомления по электронной почте", size=10)
    d.edge([worker.bottom(0.9), (worker.x + worker.w * 0.9, 676), (push.cx, 676), push.bottom(0.5)], "dir",
           "уведомления для доставки на устройство", size=10)
    d.edge([push.left(0.5), (26, push.cy), (26, 340), (320, 340), mobile.bottom(0.167)], "dir",
           "push-уведомления", size=10, label_at=0.5)
    return d


# ---------------------------------------------------------------------------
def server_components() -> Diagram:
    """UML: диаграмма компонентов серверной части."""
    d = Diagram(1180, 930)

    def group(x, y, w, title, names, columns, item_h=44):
        rows = -(-len(names) // columns)
        h = 34 + rows * (item_h + 8) + 4
        d.frame(x, y, w, h, title, fill="#FAFAFA")
        gap = 10
        item_w = (w - gap * (columns + 1)) / columns
        for index, name in enumerate(names):
            col, row = index % columns, index // columns
            d.component(x + gap + col * (item_w + gap), y + 34 + row * (item_h + 8), item_w, item_h,
                        name.split(" – "), size=11)
        return h

    d.node(40, 20, 240, 50, ["«artifact»", "Мобильное приложение"], size=11.5)
    group(40, 110, 1100, "app.routers – программный интерфейс REST (/api/v1)",
          ["auth – вход, регистрация, 2FA", "profile – профиль, уведомления, поддержка",
           "finance – счета, операции, бюджет, цели, инвестиции", "analytics – отчеты, импорт и экспорт",
           "insights – рекомендации, прогноз, риски", "sync – синхронизация",
           "admin – пользователи, журналы, мониторинг", "ml – качество и версии моделей"], 4)
    group(40, 300, 780, "app.services – бизнес-логика подсистем",
          ["entities – единые правила сохранения", "balances – балансы счетов",
           "currency – курсы валют", "budget – контроль лимитов", "goals – финансовые цели",
           "portfolio – инвестиционный портфель", "quotes – биржевые котировки",
           "reports – отчеты, главный экран", "insights – паттерны, риски, рекомендации",
           "notifier – уведомления", "importers – разбор CSV, OFX, QIF",
           "exporters – PDF, XLSX, CSV", "textparse – текст чека, голос",
           "ml_registry – версии модели", "monitoring – метрики, проверки",
           "scheduler – расписание", "tasks – очередь задач", "storage – файлы"], 3)
    group(860, 300, 280, "app.ml – машинное обучение",
          ["client – внутренний интерфейс", "categorizer – классификатор операций",
           "forecast – прогноз временных рядов"], 1)
    group(40, 700, 780, "Инфраструктура приложения",
          ["models – модели данных (ORM)", "db – сеансы и транзакции БД",
           "security – JWT, роли, хеши паролей", "audit – журналы аудита и ошибок"], 4)
    ml_service = d.component(860, 540, 280, 60, ["«service»", "ml_service – сервис", "машинного обучения"],
                             size=11)
    worker = d.component(860, 640, 280, 56, ["«process»", "app.worker – фоновые задачи"], size=11)
    db = d.cylinder(170, 840, 220, 64, ["PostgreSQL"], size=12)
    redis = d.cylinder(470, 840, 220, 64, ["Redis"], size=12)
    ext = d.node(860, 740, 280, 56, ["Внешние сервисы: Банк России,", "Московская биржа, FCM, SMTP"],
                 size=11, bold=False, dash=True)

    d.edge([(160, 70), (160, 110)], "dep", "HTTPS, JSON", label_dx=52, label_dy=4)
    d.edge([(430, 252), (430, 300)], "dep", "вызовы", label_dx=30, label_dy=4)
    d.edge([(430, 650), (430, 700)], "dep")
    d.edge([(820, 400), (860, 400)], "dep")
    d.edge([(1000, 500), ml_service.top(0.5)], "dep", "HTTP", label_dx=24, label_dy=4)
    d.edge([worker.left(0.5), (820, worker.cy)], "dep")
    d.edge([(280, 796), db.top()], "dep", "SQL", label_dx=20, label_dy=4)
    d.edge([(580, 796), redis.top()], "dep", "очередь, кеш", label_dx=46, label_dy=4)
    d.edge([worker.bottom(0.5), ext.top(0.5)], "dep")
    return d


# ---------------------------------------------------------------------------
def data_model() -> Diagram:
    """Логическая модель данных (основные таблицы)."""
    d = Diagram(1180, 1040)
    w = 215

    def table(x, y, name, fields, width=w):
        return d.cls(x, y, width, name, attrs=fields, size=10.5)

    users = table(480, 20, "users", [
        "PK id : uuid", "email : varchar(255) «unique»", "password_hash : varchar",
        "role : user | analyst | admin", "base_currency : char(3)", "risk_profile, invest_horizon_years",
        "notification_settings : json", "totp_secret, totp_enabled", "failed_logins, locked_until",
        "sync_rev : bigint"], width=250)
    accounts = table(20, 60, "accounts", [
        "PK id : uuid", "FK user_id", "name : varchar(100)", "type : cash | bank | credit | investment",
        "currency : char(3)", "initial_balance : numeric(18,2)", "is_archived : boolean"], width=250)
    categories = table(20, 300, "categories", [
        "PK id : uuid", "FK user_id", "FK parent_id «nullable»", "name : varchar(100)",
        "kind : income | expense", "is_system : boolean"], width=250)
    transactions = table(480, 300, "transactions", [
        "PK id : uuid", "FK user_id", "FK account_id", "FK to_account_id «nullable»",
        "FK category_id «nullable»", "kind : income | expense | transfer", "amount : numeric(18,2)",
        "currency : char(3)", "occurred_at : timestamptz", "merchant, comment",
        "source : manual | template | receipt | …", "auto_categorized, category_confidence",
        "external_id : varchar(64)"], width=250)
    budgets = table(20, 520, "budgets", [
        "PK id : uuid", "FK user_id", "FK category_id", "period : week | month | year",
        "limit_amount : numeric(18,2)", "warned_period, exceeded_period"], width=250)
    goals = table(940, 40, "goals", [
        "PK id : uuid", "FK user_id", "name : varchar(100)", "target_amount : numeric(18,2)",
        "deadline : date", "priority : low | medium | high", "status : active | achieved"])
    contributions = table(940, 280, "goal_contributions", [
        "PK id : uuid", "FK user_id", "FK goal_id", "amount : numeric(18,2)",
        "occurred_at : timestamptz"])
    trades = table(940, 460, "investment_trades", [
        "PK id : uuid", "FK user_id", "asset_type : stock | bond | etf | …", "ticker : varchar(30)",
        "side : buy | sell", "quantity, price : numeric(24,8)", "fee : numeric(18,2)",
        "traded_at : timestamptz"])
    quotes = table(940, 700, "quotes / quote_history", [
        "PK ticker (+ day)", "price : numeric(24,8)", "currency, as_of, source"])
    rates = table(940, 840, "exchange_rates", [
        "PK id", "currency + day «unique»", "rate_to_rub : numeric(18,6)"])
    recs = table(480, 600, "recommendations", [
        "PK id : uuid", "FK user_id", "kind, title, text, explanation",
        "estimated_saving : numeric(18,2)", "action : json", "status : new | applied | dismissed",
        "feedback : useful | not_useful"], width=250)
    notifications = table(480, 810, "notifications", [
        "PK id : uuid", "FK user_id", "kind, title, body, important",
        "event_at, push_sent_at, email_sent_at", "read_at"], width=250)
    sync = table(20, 720, "sync_operations / sync_log", [
        "PK op_id : uuid", "FK user_id", "applied_at, result",
        "pushed, pulled, conflicts"], width=250)
    system = table(20, 880, "служебные таблицы", [
        "audit_log, error_log, health_checks,", "request_stats, system_settings,",
        "ml_models, ml_training_samples, …"], width=250)
    del system

    def mult(point, text, dx=0, dy=0):
        d.text(point[0] + dx, point[1] + dy, text, 11)

    def horizontal(parent, child, y):
        """Связь «один ко многим» горизонтальной линией на высоте y."""
        left_to_right = parent.x < child.x
        p1 = (parent.x + parent.w, y) if left_to_right else (parent.x, y)
        p2 = (child.x, y) if left_to_right else (child.x + child.w, y)
        d.edge([p1, p2], "assoc")
        mult(p1, "1", 10 if left_to_right else -10, -6)
        mult(p2, "*", -10 if left_to_right else 10, -6)

    def vertical(parent, child, one="1", many="*"):
        d.edge([parent.bottom(), child.top()], "assoc")
        mult(parent.bottom(), one, 10, 15)
        mult(child.top(), many, 10, -6)

    horizontal(users, accounts, 100)
    horizontal(users, goals, 100)
    vertical(users, transactions)
    vertical(categories, budgets)
    vertical(goals, contributions)
    vertical(trades, quotes, "*", "1")
    p1, p2 = accounts.right(0.8), transactions.left(0.12)
    d.edge([p1, (400, p1[1]), (400, p2[1]), p2], "assoc")
    mult(p1, "1", 10, -6)
    mult(p2, "*", -10, -6)
    p1, p2 = categories.right(0.3), transactions.left(0.42)
    d.edge([p1, (430, p1[1]), (430, p2[1]), p2], "assoc")
    mult(p1, "1", 10, -6)
    mult(p2, "*", -10, -6)
    # Иерархия категорий: подкатегория ссылается на родительскую категорию
    cx, cy = categories.x, categories.y + 40
    d.edge([(cx, cy), (cx - 14, cy), (cx - 14, cy + 44), (cx, cy + 44)], "assoc")
    d.note(480, 965, 680, 56, [
        "Таблицы budgets, investment_trades, recommendations, notifications и sync_operations также",
        "ссылаются на users по полю user_id (связь «один ко многим»); эти линии на схеме не показаны.",
        "Синхронизируемые таблицы дополнительно содержат поля created_at, updated_at, deleted_at, rev."],
        size=10.5)
    return d


# ---------------------------------------------------------------------------
def sync_sequence() -> Diagram:
    """UML: диаграмма последовательности – ввод транзакции без сети и синхронизация."""
    d = Diagram(1180, 1010)
    names = [["Пользователь"], ["Экран «Добавить", "транзакцию»"], ["Репозиторий", "транзакций"],
             ["Локальная БД", "(Room)"], ["Модуль", "синхронизации"], ["Серверное", "приложение"],
             ["PostgreSQL"]]
    xs = [70, 225, 380, 535, 690, 890, 1090]
    for x, lines in zip(xs, names, strict=True):
        d.node(x - 66, 20, 132, 46, lines, size=11.5)
        d.items.append(f'<line x1="{x}" y1="66" x2="{x}" y2="990" stroke="#1a1a1a" '
                       f'stroke-dasharray="5,4"/>')

    def activation(index, y1, y2):
        d.rect(xs[index] - 6, y1, 12, y2 - y1, fill="#EDEDED")

    def message(a, b, y, text, kind="msg", size=10.5):
        offset = 6 if b > a else -6
        d.edge([(xs[a] + offset, y), (xs[b] - offset, y)], kind)
        lines = text.split("\n")
        for index, line in enumerate(lines):
            d.text((xs[a] + xs[b]) / 2, y - 7 - (len(lines) - 1 - index) * 13, line, size)

    def self_message(index, y, text, side="right"):
        x = xs[index] + 6
        d.edge([(x, y), (x + 30, y), (x + 30, y + 24), (x, y + 24)], "msg")
        for line_index, line in enumerate(text.split("\n")):
            if side == "right":
                d.text(x + 38, y + 8 + line_index * 13, line, 10.5, anchor="start")
            else:
                d.text(xs[index] - 14, y + 4 + line_index * 13, line, 10.5, anchor="end")

    def fragment(y1, y2, label, condition, x1=20, x2=1160, condition_dx=64):
        d.rect(x1, y1, x2 - x1, y2 - y1, fill="none", layer=d.back)
        d.back.append(f'<path d="M{x1},{y1} h54 v14 l-8,8 h-46 Z" fill="#fff" stroke="#1a1a1a"/>')
        d.text(x1 + 8, y1 + 15, label, 11, anchor="start", weight="bold", layer=d.back)
        d.text(x1 + condition_dx, y1 + 15, condition, 10.5, anchor="start", style="italic",
               layer=d.back)

    activation(1, 110, 262)
    activation(2, 140, 232)
    activation(3, 172, 212)
    message(0, 1, 110, "ввести сумму,\nнажать «Сохранить»")
    message(1, 2, 140, "save(transaction)")
    message(2, 3, 172, "INSERT transactions;\nINSERT pending_operations", size=10)
    message(3, 2, 212, "записано\n(одна транзакция БД)", "ret")
    message(2, 1, 232, "результат", "ret")
    message(1, 0, 262, "«Транзакция добавлена»", "ret", size=10)

    fragment(290, 360, "opt", "[нет подключения к сети Интернет]")
    self_message(4, 316, "операция остается в очереди,\nстатус «офлайн, ожидает: 1»")

    fragment(380, 980, "opt", "[подключение к сети Интернет восстановлено]")
    activation(4, 420, 940)
    message(4, 3, 428, "прочитать очередь")
    message(3, 4, 470, "операции: op_id, entity,\nid, updated_at, data", "ret", size=10)
    activation(5, 520, 800)
    message(4, 5, 520, "POST /api/v1/sync\n{since_rev, changes}")
    self_message(5, 540, "для каждой операции:\nповтор (op_id уже применен)?\nконфликт (запись изменена позже)?",
                 side="left")
    fragment(600, 690, "alt", "[операция новая и не устарела]", x1=760, x2=1150, condition_dx=146)
    activation(6, 640, 670)
    message(5, 6, 640, "INSERT / UPDATE; rev = rev + 1")
    message(6, 5, 670, "записано", "ret")
    self_message(5, 706, "автокатегоризация,\nконтроль бюджета,\nуведомления", side="left")
    message(5, 4, 800, "results[applied | duplicate |\nconflict | rejected], changes\nс rev > since_rev, новый rev",
            "ret", size=10)
    activation(3, 860, 895)
    message(4, 3, 860, "применить изменения\nсервера, очистить очередь", size=10)
    message(3, 4, 895, "готово", "ret")
    message(4, 1, 925, "статус «синхронизировано»", "ret")
    message(1, 0, 958, "обновленные данные\nна экране", "ret")
    return d


def mobile_components() -> Diagram:
    """UML: диаграмма компонентов мобильного приложения."""
    d = Diagram(1180, 860)

    def group(x, y, w, title, names, columns, item_h=44, size=10.5):
        rows = -(-len(names) // columns)
        h = 34 + rows * (item_h + 8) + 4
        d.frame(x, y, w, h, title, fill="#FAFAFA", size=11.5)
        gap = 10
        item_w = (w - gap * (columns + 1)) / columns
        for index, name in enumerate(names):
            col, row = index % columns, index // columns
            d.component(x + gap + col * (item_w + gap), y + 34 + row * (item_h + 8), item_w, item_h,
                        name.split(" – "), size=size)
        return Box(x, y, w, h)

    ui = group(40, 20, 860, "ui – экраны Jetpack Compose и модели представления (ViewModel)",
               ["auth – вход, регистрация", "dashboard – главный экран",
                "transactions – операции, справочники", "budget – бюджетные лимиты",
                "goals – финансовые цели", "investments – портфель, актив", "reports – отчеты, экспорт",
                "recommendations – советы, прогноз", "profile – профиль, безопасность",
                "admin – администрирование"], 5)
    activity = d.component(930, 20, 210, 56, ["«activity»", "MainActivity"], size=11)
    container = d.component(930, 96, 210, 66, ["«container»", "di.AppContainer", "создание зависимостей"],
                            size=10.5)
    domain = group(40, 210, 420, "domain – расчеты на устройстве",
                   ["BalanceCalculator – балансы счетов", "BudgetCalculator – исполнение лимитов",
                    "GoalCalculator – прогресс и прогноз", "PortfolioCalculator – позиции, доходность",
                    "ReportBuilder – отчеты, главный экран", "CategorySuggester – категория по истории",
                    "ReceiptParser – разбор текста чека", "VoiceParser – разбор голосовой фразы"], 2)
    repo = group(480, 210, 420, "data.repo – репозитории",
                 ["FinanceRepository – учетные данные", "AuthRepository – вход, выход",
                  "ProfileRepository – профиль, настройки", "InvestmentRepository – котировки",
                  "InsightsRepository – рекомендации", "FileRepository – импорт, экспорт",
                  "CacheStore – копии ответов сервера", "ReceiptRecognizer – ML Kit"], 2)
    notify = group(930, 210, 210, "notify – уведомления",
                   ["LocalAlerts – правила на устройстве", "ServerNotifications – уведомления сервера",
                    "Notifier – каналы Android", "PushService – FCM"], 1)
    remote = group(40, 510, 200, "data.remote",
                   ["FinHelperApi – Retrofit, /api/v1", "NetworkModule – OkHttp, токены",
                    "ApiError – тексты ошибок"], 1)
    sync = group(260, 510, 420, "data.sync – синхронизация",
                 ["SyncQueue – очередь операций", "SyncEngine – сеанс обмена",
                  "SyncManager – запуск, индикатор", "SyncWorker – фоновая задача",
                  "SyncAdapters – виды записей", "ConnectivityObserver – состояние сети"], 2)
    local = group(700, 510, 200, "data.local",
                  ["AppDatabase – Room, 13 таблиц", "Daos – запросы к таблицам",
                   "DatabaseFactory – SQLCipher"], 1)
    prefs = group(930, 510, 210, "data.prefs",
                  ["SecureStore – шифрование AES-GCM", "SessionStore – токены, роль",
                   "SettingsStore – настройки"], 1)

    server = d.node(40, 770, 200, 60, ["Серверное приложение", "REST, /api/v1"], size=11)
    system = d.node(290, 770, 360, 60, ["WorkManager, ConnectivityManager", "(службы Android)"],
                    size=11, bold=False, dash=True)
    db = d.cylinder(700, 765, 200, 74, ["finhelper.db", "SQLCipher, AES-256"], size=11)
    keystore = d.node(930, 770, 210, 60, ["Android Keystore", "ключ AES-256"], size=11)

    d.edge([activity.left(0.5), (ui.x + ui.w, activity.cy)], "dep")
    d.edge([container.left(0.5), (ui.x + ui.w, container.cy)], "dep")
    d.edge([(250, ui.y + ui.h), (250, domain.y)], "dep", "расчеты", label_dx=34, label_dy=4)
    d.edge([(690, ui.y + ui.h), (690, repo.y)], "dep", "вызовы", label_dx=30, label_dy=4)
    d.edge([(repo.x, 330), (domain.x + domain.w, 330)], "dep")
    d.edge([(notify.x, 330), (repo.x + repo.w, 330)], "dep")
    d.edge([(500, repo.y + repo.h), (500, 483), (140, 483), (140, remote.y)], "dep")
    d.edge([(560, repo.y + repo.h), (560, sync.y)], "dep")
    d.edge([(800, repo.y + repo.h), (800, local.y)], "dep")
    d.edge([(sync.x, 600), (remote.x + remote.w, 600)], "dep")
    d.edge([(sync.x + sync.w, 600), (local.x, 600)], "dep")
    d.edge([(local.x + local.w, 600), (prefs.x, 600)], "dep")
    d.edge([(140, remote.y + remote.h), (140, server.y)], "dep", "HTTPS, JSON", label_dx=42, label_dy=4)
    d.edge([(470, sync.y + sync.h), (470, system.y)], "dep")
    d.edge([(800, local.y + local.h), (800, db.y)], "dep")
    d.edge([(1035, prefs.y + prefs.h), (1035, keystore.y)], "dep")
    return d


# ---------------------------------------------------------------------------
DIAGRAMS = {
    "archimate_layers": archimate,
    "uml_components_mobile": mobile_components,
    "sysml_bdd": sysml_bdd,
    "sysml_ibd": sysml_ibd,
    "uml_use_cases": use_cases,
    "uml_components_server": server_components,
    "data_model": data_model,
    "uml_sequence_sync": sync_sequence,
    "uml_deployment": deployment,
}

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "svg"
    only = set(sys.argv[2:])
    os.makedirs(target, exist_ok=True)
    for name, builder in DIAGRAMS.items():
        if only and name not in only:
            continue
        builder().save(os.path.join(target, f"{name}.svg"))
        print(name)
