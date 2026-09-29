package com.paperscreen

/**
 * Groups an alphabetical app list under letter headings for the app drawer. Accented
 * letters go under their base letter (É under E); names that don't start with a letter go
 * under "#", which comes last. Groups keep the incoming order.
 */
object AppSections {

    class Section<T>(val letter: String, val items: List<T>)

    const val OTHER = "#"

    fun <T> build(items: List<T>, label: (T) -> String): List<Section<T>> {
        val groups = LinkedHashMap<String, MutableList<T>>()
        for (item in items) groups.getOrPut(key(label(item))) { ArrayList() } += item
        val other = groups.remove(OTHER)
        val sections = groups.map { (letter, members) -> Section(letter, members) }
        return if (other == null) sections else sections + Section(OTHER, other)
    }

    fun key(label: String): String {
        val first = AppSearch.normalize(label.trim()).firstOrNull() ?: return OTHER
        return if (first.isLetter()) first.uppercase() else OTHER
    }
}
