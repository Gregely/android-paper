package com.paperscreen

/**
 * The home screen's favourite slots, stored as one string: one flattened ComponentName per
 * slot, separated by newlines, with an empty line for an empty slot.
 */
object Favourites {
    const val SLOTS = 5

    fun decode(stored: String?): List<String?> {
        val parts = stored.orEmpty().split('\n')
        return List(SLOTS) { i -> parts.getOrNull(i)?.takeIf { it.isNotBlank() } }
    }

    fun encode(slots: List<String?>): String =
        List(SLOTS) { i -> slots.getOrNull(i).orEmpty() }.joinToString("\n")
}
