package com.paperscreen

/**
 * The home screen's favourites: an ordered list of up to [MAX] apps, stored as one string
 * of flattened ComponentNames separated by newlines. Blank entries (from the older
 * fixed-slot format) are skipped.
 */
object Favourites {
    const val MAX = 10

    fun decode(stored: String?): List<String> =
        stored.orEmpty().split('\n').filter { it.isNotBlank() }.take(MAX)

    fun encode(favourites: List<String>): String = favourites.take(MAX).joinToString("\n")

    /** Whether [app] can be added: it isn't a favourite yet and there's room. */
    fun canAdd(favourites: List<String>, app: String) = app !in favourites && favourites.size < MAX

    /** [app] added at the end, if [canAdd]; otherwise unchanged. */
    fun add(favourites: List<String>, app: String): List<String> =
        if (canAdd(favourites, app)) favourites + app else favourites

    fun remove(favourites: List<String>, app: String): List<String> = favourites - app

    fun moveToTop(favourites: List<String>, app: String): List<String> =
        if (app in favourites) listOf(app) + (favourites - app) else favourites

    fun moveToBottom(favourites: List<String>, app: String): List<String> =
        if (app in favourites) (favourites - app) + app else favourites
}
