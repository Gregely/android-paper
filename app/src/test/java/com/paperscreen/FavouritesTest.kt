package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class FavouritesTest {

    @Test
    fun missingStorageIsEmpty() {
        assertEquals(emptyList<String>(), Favourites.decode(null))
    }

    @Test
    fun roundTripKeepsOrder() {
        val favourites = listOf("c/.C", "a/.A", "b/.B")
        assertEquals(favourites, Favourites.decode(Favourites.encode(favourites)))
    }

    @Test
    fun olderSlotFormatIsCompacted() {
        // The previous home screen stored five fixed slots, with blank lines for empty ones.
        assertEquals(listOf("a/.A", "b/.B"), Favourites.decode("a/.A\n\nb/.B\n\n"))
    }

    @Test
    fun atMostSixAreKept() {
        assertEquals(Favourites.MAX, Favourites.decode((1..9).joinToString("\n")).size)
        assertEquals(Favourites.MAX, Favourites.decode(Favourites.encode((1..9).map { "$it" })).size)
    }
}
