"""Контрольная выборка для оценки точности автоматической категоризации.

Показатели качества модели (accuracy, precision, recall, F1-score) рассчитываются
на этой выборке после каждого обучения (п. 4.3.1.3, 4.3.1.5 ТЗ). Операции
контрольной выборки никогда не используются для обучения.

80 операций с заранее известными (эталонными) категориями в том виде, в каком
они встречаются в банковских выписках. Первые 60 операций относятся к известным
торговым точкам, но записаны иначе, чем в обучающем наборе (другие суффиксы,
регистр, транслитерация). Последние 20 операций – торговые точки и назначения
платежей, которых в обучающем наборе нет вовсе: по ним видно, как модель
обобщает. Используется в п. 4.1.5 программы и методики испытаний.
"""

CONTROL_SET: list[dict] = [
    # --- Продукты
    {"text": "PYATEROCHKA 14872 EKATERINBURG RUS", "kind": "expense", "category": "Продукты"},
    {"text": "МАГНИТ У ДОМА ул. Ленина 5", "kind": "expense", "category": "Продукты"},
    {"text": "PEREKRESTOK EXPRESS MOSKVA", "kind": "expense", "category": "Продукты"},
    {"text": "Оплата в ВКУСВИЛЛ 2331", "kind": "expense", "category": "Продукты"},
    {"text": "SAMOKAT.RU доставка продуктов", "kind": "expense", "category": "Продукты"},
    {"text": "Гипермаркет ЛЕНТА-112", "kind": "expense", "category": "Продукты"},
    {"text": "Кировский супермаркет чек 5541", "kind": "expense", "category": "Продукты"},
    # --- Кафе
    {"text": "VKUSNO I TOCHKA 20455", "kind": "expense", "category": "Кафе"},
    {"text": "DODO PIZZA EKB", "kind": "expense", "category": "Кафе"},
    {"text": "Кофейня COFFEE LIKE на Малышева", "kind": "expense", "category": "Кафе"},
    {"text": "YANDEX.EDA заказ 884512", "kind": "expense", "category": "Кафе"},
    {"text": "Ресторан Хмели-Сунели", "kind": "expense", "category": "Кафе"},
    {"text": "Столовая №1 бизнес-ланч", "kind": "expense", "category": "Кафе"},
    {"text": "BURGER KING 0432 RUS", "kind": "expense", "category": "Кафе"},
    # --- Транспорт
    {"text": "YANDEX.TAXI поездка", "kind": "expense", "category": "Транспорт"},
    {"text": "LUKOIL AZS 66123 топливо АИ-95", "kind": "expense", "category": "Транспорт"},
    {"text": "Метрополитен проезд", "kind": "expense", "category": "Транспорт"},
    {"text": "GAZPROMNEFT AZS 214", "kind": "expense", "category": "Транспорт"},
    {"text": "Парковка ТЦ Гринвич", "kind": "expense", "category": "Транспорт"},
    {"text": "DELIMOBIL аренда авто", "kind": "expense", "category": "Транспорт"},
    # --- Жилье
    {"text": "Оплата ЖКХ за октябрь ЕИРЦ", "kind": "expense", "category": "Жилье"},
    {"text": "ЕКАТЕРИНБУРГЭНЕРГОСБЫТ электроэнергия", "kind": "expense", "category": "Жилье"},
    {"text": "Аренда квартиры ноябрь", "kind": "expense", "category": "Жилье"},
    {"text": "LEROY MERLIN EKATERINBURG", "kind": "expense", "category": "Жилье"},
    {"text": "Водоканал водоснабжение л/с 4455", "kind": "expense", "category": "Жилье"},
    # --- Связь
    {"text": "MTS оплата мобильной связи", "kind": "expense", "category": "Связь"},
    {"text": "Ростелеком домашний интернет", "kind": "expense", "category": "Связь"},
    {"text": "BEELINE пополнение 9221234567", "kind": "expense", "category": "Связь"},
    {"text": "TELE2 абонентская плата", "kind": "expense", "category": "Связь"},
    # --- Здоровье
    {"text": "APTEKA RIGLA 1123", "kind": "expense", "category": "Здоровье"},
    {"text": "ИНВИТРО анализы", "kind": "expense", "category": "Здоровье"},
    {"text": "Стоматология Дентал-Люкс", "kind": "expense", "category": "Здоровье"},
    {"text": "Аптека Живика №45", "kind": "expense", "category": "Здоровье"},
    # --- Одежда
    {"text": "SPORTMASTER 0098 EKATERINBURG", "kind": "expense", "category": "Одежда"},
    {"text": "LAMODA.RU заказ одежды", "kind": "expense", "category": "Одежда"},
    {"text": "GLORIA JEANS ТРЦ Мега", "kind": "expense", "category": "Одежда"},
    {"text": "Магазин обуви KARI", "kind": "expense", "category": "Одежда"},
    # --- Развлечения
    {"text": "KINOPOISK подписка", "kind": "expense", "category": "Развлечения"},
    {"text": "Кинотеатр Синема Парк билеты", "kind": "expense", "category": "Развлечения"},
    {"text": "STEAMGAMES.COM покупка игры", "kind": "expense", "category": "Развлечения"},
    {"text": "YANDEX PLUS ежемесячный платеж", "kind": "expense", "category": "Развлечения"},
    {"text": "Театр драмы билеты", "kind": "expense", "category": "Развлечения"},
    # --- Образование
    {"text": "SKILLBOX оплата курса", "kind": "expense", "category": "Образование"},
    {"text": "Оплата обучения УрФУ семестр", "kind": "expense", "category": "Образование"},
    {"text": "Репетитор английский язык", "kind": "expense", "category": "Образование"},
    # --- Путешествия
    {"text": "AEROFLOT авиабилеты SVX-LED", "kind": "expense", "category": "Путешествия"},
    {"text": "RZD билет на поезд", "kind": "expense", "category": "Путешествия"},
    {"text": "OSTROVOK.RU бронирование отеля", "kind": "expense", "category": "Путешествия"},
    # --- Кредиты
    {"text": "Погашение кредита договор 55123", "kind": "expense", "category": "Кредиты"},
    {"text": "Ежемесячный платеж по ипотеке", "kind": "expense", "category": "Кредиты"},
    # --- Дом и быт
    {"text": "WILDBERRIES заказ 77812", "kind": "expense", "category": "Дом и быт"},
    {"text": "OZON.RU оплата заказа", "kind": "expense", "category": "Дом и быт"},
    {"text": "DNS SHOP EKATERINBURG", "kind": "expense", "category": "Дом и быт"},
    {"text": "Золотое яблоко косметика", "kind": "expense", "category": "Дом и быт"},
    # --- Подарки
    {"text": "FLOWWOW букет цветов", "kind": "expense", "category": "Подарки"},
    # --- Доходы
    {"text": "Зачисление заработной платы за октябрь", "kind": "income", "category": "Зарплата"},
    {"text": "Аванс ООО Ромашка", "kind": "income", "category": "Зарплата"},
    {"text": "Проценты по вкладу Накопительный", "kind": "income", "category": "Проценты и дивиденды"},
    {"text": "Выплата дивидендов SBER", "kind": "income", "category": "Проценты и дивиденды"},
    {"text": "Возврат покупки OZON", "kind": "income", "category": "Подарки и возвраты"},
    # --- Торговые точки и формулировки, отсутствующие в обучающем наборе
    {"text": "SPAR 88 EKATERINBURG", "kind": "expense", "category": "Продукты"},
    {"text": "Бахетле супермаркет", "kind": "expense", "category": "Продукты"},
    {"text": "Жар-Свежар", "kind": "expense", "category": "Продукты"},
    {"text": "Мясной двор ИП Иванов", "kind": "expense", "category": "Продукты"},
    {"text": "Ёбидоёби роллы", "kind": "expense", "category": "Кафе"},
    {"text": "Грузинская кухня Сулико", "kind": "expense", "category": "Кафе"},
    {"text": "Simple Coffee эспрессо-бар", "kind": "expense", "category": "Кафе"},
    {"text": "Трамвайно-троллейбусное управление", "kind": "expense", "category": "Транспорт"},
    {"text": "Башнефть АЗС 77", "kind": "expense", "category": "Транспорт"},
    {"text": "Замена масла СТО Вираж", "kind": "expense", "category": "Транспорт"},
    {"text": "Т Плюс тепловая энергия", "kind": "expense", "category": "Жилье"},
    {"text": "Взнос в СНТ Рассвет", "kind": "expense", "category": "Жилье"},
    {"text": "Планета Здоровья лекарственные препараты", "kind": "expense", "category": "Здоровье"},
    {"text": "МРТ коленного сустава", "kind": "expense", "category": "Здоровье"},
    {"text": "Спортивный клуб абонемент", "kind": "expense", "category": "Развлечения"},
    {"text": "Bookmate ежемесячная подписка", "kind": "expense", "category": "Развлечения"},
    {"text": "Школа программирования для детей", "kind": "expense", "category": "Образование"},
    {"text": "Azimut Hotel Sochi", "kind": "expense", "category": "Путешествия"},
    {"text": "Перечисление отпускных и премии", "kind": "income", "category": "Зарплата"},
    {"text": "Купонный доход ОФЗ 26238", "kind": "income", "category": "Проценты и дивиденды"},
]
