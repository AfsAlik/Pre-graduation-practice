package ru.finhelper.app.data.local

import ru.finhelper.app.core.Ids
import java.util.UUID

/**
 * Системный справочник категорий (п. 4.3.2.3 ТЗ).
 *
 * Повторяет справочник серверной части (services/categories.py): названия и
 * пространство имен UUID совпадают, поэтому идентификаторы категорий на устройстве
 * и на сервере одинаковы и категории доступны до первой синхронизации.
 */
object SystemCategories {
    private val NAMESPACE = UUID.fromString("6f1c2f0e-6a57-4f0b-9d63-1f1d2c0a7b11")

    private class Seed(val name: String, val kind: String, val icon: String, val color: String, val children: List<String> = emptyList())

    private val SEEDS = listOf(
        Seed("Продукты", "expense", "shopping_cart", "#2E7D32", listOf("Супермаркеты", "Рынок")),
        Seed("Кафе", "expense", "restaurant", "#EF6C00", listOf("Рестораны", "Кофейни", "Доставка еды")),
        Seed("Транспорт", "expense", "directions_bus", "#1565C0", listOf("Такси", "Топливо", "Проезд")),
        Seed("Жилье", "expense", "home", "#6A1B9A", listOf("Аренда", "Коммунальные услуги", "Ремонт")),
        Seed("Связь", "expense", "phone_android", "#00838F", listOf("Мобильная связь", "Интернет")),
        Seed("Здоровье", "expense", "medical_services", "#C62828", listOf("Аптеки", "Врачи")),
        Seed("Одежда", "expense", "checkroom", "#AD1457"),
        Seed("Развлечения", "expense", "movie", "#4527A0", listOf("Кино", "Подписки", "Игры")),
        Seed("Образование", "expense", "school", "#283593"),
        Seed("Путешествия", "expense", "flight", "#00695C"),
        Seed("Кредиты", "expense", "account_balance", "#B71C1C", listOf("Ипотека", "Потребительский кредит")),
        Seed("Подарки", "expense", "redeem", "#D81B60"),
        Seed("Дом и быт", "expense", "chair", "#5D4037"),
        Seed("Прочее", "expense", "more_horiz", "#546E7A"),
        Seed("Зарплата", "income", "payments", "#2E7D32"),
        Seed("Подработка", "income", "work", "#558B2F"),
        Seed("Проценты и дивиденды", "income", "savings", "#00796B"),
        Seed("Подарки и возвраты", "income", "card_giftcard", "#7CB342"),
        Seed("Прочие доходы", "income", "add_circle", "#689F38"),
    )

    /** Постоянный идентификатор системной категории; для подкатегории имя имеет вид «Категория/Подкатегория». */
    fun id(name: String): String = Ids.uuid5(NAMESPACE, name)

    /** Полный перечень системных категорий и подкатегорий. */
    fun all(): List<CategoryEntity> = SEEDS.flatMap { seed ->
        val parent = CategoryEntity(
            id = id(seed.name), name = seed.name, kind = seed.kind, icon = seed.icon, color = seed.color, isSystem = true,
        )
        listOf(parent) + seed.children.map { child ->
            parent.copy(id = id("${seed.name}/$child"), name = child, parentId = parent.id)
        }
    }
}
