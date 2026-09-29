package com.paperscreen

/**
 * Groups an alphabetical app list under letter headings for the app drawer. Names that
 * don't belong to a letter (digits, symbols, emoji) go under "#", which comes last. Groups
 * keep the incoming order.
 *
 * The heading for a name comes from [key]: on the device that's [LocaleSectionKeys], which
 * understands every script; the default, [key] below, handles accented Latin letters.
 */
object AppSections {

    class Section<T>(val letter: String, val items: List<T>)

    const val OTHER = "#"

    fun <T> build(items: List<T>, label: (T) -> String, key: (String) -> String = ::key): List<Section<T>> {
        val groups = LinkedHashMap<String, MutableList<T>>()
        for (item in items) groups.getOrPut(key(label(item))) { ArrayList() } += item
        val other = groups.remove(OTHER)
        val sections = groups.map { (letter, members) -> Section(letter, members) }
        return if (other == null) sections else sections + Section(OTHER, other)
    }

    /** Base letter of the first character (É → E), or [OTHER] if it isn't a letter. */
    fun key(label: String): String {
        val first = AppSearch.normalize(label.trim()).firstOrNull() ?: return OTHER
        return if (first.isLetter()) first.uppercase() else OTHER
    }
}
