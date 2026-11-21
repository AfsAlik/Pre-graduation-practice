"""Справочник категорий: системные категории и дерево подкатегорий."""
import uuid

from sqlalchemy import or_
from sqlalchemy.orm import Session

from ..models import Category, User

SYSTEM_USER_ID = "00000000-0000-0000-0000-000000000000"
_NAMESPACE = uuid.UUID("6f1c2f0e-6a57-4f0b-9d63-1f1d2c0a7b11")

# (название, вид, пиктограмма, цвет, подкатегории)
SYSTEM_CATEGORIES: list[tuple[str, str, str, str, list[str]]] = [
    ("Продукты", "expense", "shopping_cart", "#2E7D32", ["Супермаркеты", "Рынок"]),
    ("Кафе", "expense", "restaurant", "#EF6C00", ["Рестораны", "Кофейни", "Доставка еды"]),
    ("Транспорт", "expense", "directions_bus", "#1565C0", ["Такси", "Топливо", "Проезд"]),
    ("Жилье", "expense", "home", "#6A1B9A", ["Аренда", "Коммунальные услуги", "Ремонт"]),
    ("Связь", "expense", "phone_android", "#00838F", ["Мобильная связь", "Интернет"]),
    ("Здоровье", "expense", "medical_services", "#C62828", ["Аптеки", "Врачи"]),
    ("Одежда", "expense", "checkroom", "#AD1457", []),
    ("Развлечения", "expense", "movie", "#4527A0", ["Кино", "Подписки", "Игры"]),
    ("Образование", "expense", "school", "#283593", []),
    ("Путешествия", "expense", "flight", "#00695C", []),
    ("Кредиты", "expense", "account_balance", "#B71C1C", ["Ипотека", "Потребительский кредит"]),
    ("Подарки", "expense", "redeem", "#D81B60", []),
    ("Дом и быт", "expense", "chair", "#5D4037", []),
    ("Прочее", "expense", "more_horiz", "#546E7A", []),
    ("Зарплата", "income", "payments", "#2E7D32", []),
    ("Подработка", "income", "work", "#558B2F", []),
    ("Проценты и дивиденды", "income", "savings", "#00796B", []),
    ("Подарки и возвраты", "income", "card_giftcard", "#7CB342", []),
    ("Прочие доходы", "income", "add_circle", "#689F38", []),
]


def system_category_id(name: str) -> str:
    """Постоянный идентификатор системной категории (одинаков на всех установках)."""
    return str(uuid.uuid5(_NAMESPACE, name))


def seed_system_categories(db: Session) -> int:
    """Заполняет справочник системных категорий (выполняется при развертывании)."""
    if db.get(User, SYSTEM_USER_ID) is None:
        # Служебная запись-владелец системных справочников; вход под ней невозможен
        db.add(
            User(
                id=SYSTEM_USER_ID, email="system@finhelper.local", display_name="Система",
                password_hash="!", is_blocked=True, email_confirmed=True,
            )
        )
        db.flush()
    existing = {c.id for c in db.query(Category.id).filter(Category.is_system.is_(True))}
    created = 0
    for name, kind, icon, color, children in SYSTEM_CATEGORIES:
        parent_id = system_category_id(name)
        if parent_id not in existing:
            db.add(
                Category(
                    id=parent_id, user_id=SYSTEM_USER_ID, name=name, kind=kind,
                    icon=icon, color=color, is_system=True,
                )
            )
            created += 1
        for child in children:
            child_id = system_category_id(f"{name}/{child}")
            if child_id not in existing:
                db.add(
                    Category(
                        id=child_id, user_id=SYSTEM_USER_ID, name=child, kind=kind,
                        parent_id=parent_id, icon=icon, color=color, is_system=True,
                    )
                )
                created += 1
    db.flush()
    return created


def visible_categories(db: Session, user_id: str, *, include_deleted: bool = False):
    """Категории, доступные пользователю: системные и собственные."""
    query = db.query(Category).filter(
        or_(Category.is_system.is_(True), Category.user_id == user_id)
    )
    if not include_deleted:
        query = query.filter(Category.deleted_at.is_(None))
    return query


def category_map(db: Session, user_id: str) -> dict[str, Category]:
    return {c.id: c for c in visible_categories(db, user_id, include_deleted=True)}


def subtree_ids(categories: dict[str, Category], root_id: str) -> set[str]:
    """Идентификаторы категории и всех ее подкатегорий."""
    result = {root_id}
    changed = True
    while changed:
        changed = False
        for category in categories.values():
            if category.parent_id in result and category.id not in result:
                result.add(category.id)
                changed = True
    return result


def root_of(categories: dict[str, Category], category_id: str | None) -> Category | None:
    """Категория верхнего уровня для категории или подкатегории."""
    seen: set[str] = set()
    current = categories.get(category_id) if category_id else None
    while current is not None and current.parent_id and current.parent_id not in seen:
        seen.add(current.id)
        parent = categories.get(current.parent_id)
        if parent is None:
            break
        current = parent
    return current


def find_by_name(db: Session, user_id: str, name: str, kind: str | None = None) -> Category | None:
    query = visible_categories(db, user_id).filter(Category.name == name)
    if kind:
        query = query.filter(Category.kind == kind)
    return query.order_by(Category.is_system.desc()).first()
