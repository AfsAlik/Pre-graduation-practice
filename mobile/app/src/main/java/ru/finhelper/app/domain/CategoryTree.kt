package ru.finhelper.app.domain

import ru.finhelper.app.data.local.CategoryEntity

/** Операции над деревом категорий и подкатегорий. */
object CategoryTree {
    /** Идентификаторы категории и всех ее подкатегорий. */
    fun subtreeIds(categories: Map<String, CategoryEntity>, rootId: String): Set<String> {
        val result = mutableSetOf(rootId)
        var changed = true
        while (changed) {
            changed = false
            for (category in categories.values) {
                if (category.parentId in result && result.add(category.id)) changed = true
            }
        }
        return result
    }

    /** Категория верхнего уровня для категории или подкатегории. */
    fun rootOf(categories: Map<String, CategoryEntity>, categoryId: String?): CategoryEntity? {
        var current = categories[categoryId ?: return null] ?: return null
        val seen = mutableSetOf<String>()
        while (current.parentId != null && seen.add(current.id)) {
            current = categories[current.parentId] ?: break
        }
        return current
    }

    /** Категория и все ее родители (для поиска лимитов, действующих на подкатегорию). */
    fun chain(categories: Map<String, CategoryEntity>, categoryId: String?): Set<String> {
        val result = linkedSetOf<String>()
        var current = categories[categoryId ?: return result]
        while (current != null && result.add(current.id)) {
            current = current.parentId?.let(categories::get)
        }
        return result
    }

    /** Название для списков: «Кафе» либо «Кафе / Кофейни» для подкатегории. */
    fun displayName(categories: Map<String, CategoryEntity>, categoryId: String?): String {
        val category = categories[categoryId ?: return "Без категории"] ?: return "Без категории"
        val parent = category.parentId?.let(categories::get)
        return if (parent != null) "${parent.name} / ${category.name}" else category.name
    }

    /** Категории указанного вида в порядке «категория, затем ее подкатегории». */
    fun ordered(categories: Collection<CategoryEntity>, kind: String): List<CategoryEntity> {
        val ofKind = categories.filter { it.kind == kind }
        val children = ofKind.filter { it.parentId != null }.groupBy { it.parentId }
        return ofKind.filter { it.parentId == null }.sortedBy { it.name }
            .flatMap { root -> listOf(root) + children[root.id].orEmpty().sortedBy { it.name } }
    }
}
