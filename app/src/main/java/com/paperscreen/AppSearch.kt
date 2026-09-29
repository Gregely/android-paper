package com.paperscreen

import java.text.Normalizer

/**
 * Filters the home screen's app list as the user types. Matching ignores case and accents.
 * Results are ordered: label starts with the query, then a word in it does, then it
 * contains the query anywhere. Within each group the incoming (alphabetical) order is kept.
 */
object AppSearch {

    fun <T> filter(items: List<T>, query: String, label: (T) -> String): List<T> {
        val q = normalize(query).trim()
        if (q.isEmpty()) return items
        val prefix = ArrayList<T>()
        val wordStart = ArrayList<T>()
        val anywhere = ArrayList<T>()
        for (item in items) {
            val l = normalize(label(item))
            when {
                l.startsWith(q) -> prefix += item
                l.split(' ', '-', '.', '_').any { it.startsWith(q) } -> wordStart += item
                l.contains(q) -> anywhere += item
            }
        }
        return prefix + wordStart + anywhere
    }

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()

    private val DIACRITICS = Regex("\\p{Mn}+")
}
