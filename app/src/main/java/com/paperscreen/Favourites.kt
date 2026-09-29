package com.paperscreen

/**
 * The home screen's favourites: an ordered list of up to [MAX] apps, stored as one string
 * of flattened ComponentNames separated by newlines. Blank entries (from the older
 * fixed-slot format) are skipped.
 */
object Favourites {
    const val MAX = 6

    fun decode(stored: String?): List<String> =
        stored.orEmpty().split('\n').filter { it.isNotBlank() }.take(MAX)

    fun encode(favourites: List<String>): String = favourites.take(MAX).joinToString("\n")
}
