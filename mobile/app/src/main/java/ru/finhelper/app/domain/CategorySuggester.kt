package ru.finhelper.app.domain

import ru.finhelper.app.data.local.CategoryEntity
import ru.finhelper.app.data.local.TransactionEntity

/**
 * Автоматическая категоризация на основе ранее выполненных операций пользователя
 * (п. 4.2.1.1 г ТЗ). Используется без сети; при наличии сети категория уточняется
 * моделью серверной части.
 */
object CategorySuggester {
    private val SEPARATORS = Regex("[^a-zа-я0-9]+")

    /** Приводит описание операции к виду для сравнения: нижний регистр, без знаков препинания. */
    fun normalize(text: String): String =
        text.lowercase().replace('ё', 'е').replace(SEPARATORS, " ").trim()

    /**
     * Подбирает категорию для описания операции.
     *
     * Сначала ищется последняя операция того же вида с таким же описанием; если ее
     * нет – категория, название которой встречается в описании.
     *
     * @return идентификатор категории либо null
     */
    fun suggest(
        text: String,
        kind: String,
        history: List<TransactionEntity>,
        categories: Collection<CategoryEntity>,
    ): String? {
        val wanted = normalize(text)
        if (wanted.isEmpty()) return null
        val known = categories.filter { it.kind == kind && it.deletedAt == null }.associateBy { it.id }
        history.asSequence()
            .filter { it.deletedAt == null && it.kind == kind && it.categoryId in known }
            .filter { normalize(it.merchant) == wanted || normalize(it.comment) == wanted }
            .maxByOrNull { it.occurredAt }
            ?.let { return it.categoryId }
        val words = wanted.split(' ').toSet()
        return known.values
            .filter { category -> normalize(category.name).let { it == wanted || it in words } }
            // Подкатегория точнее категории, поэтому при совпадении предпочитается она
            .maxByOrNull { if (it.parentId != null) 1 else 0 }
            ?.id
    }
}
