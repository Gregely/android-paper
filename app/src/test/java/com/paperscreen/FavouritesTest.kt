package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun atMostTenAreKept() {
        assertEquals(10, Favourites.MAX)
        assertEquals(Favourites.MAX, Favourites.decode((1..14).joinToString("\n")).size)
        assertEquals(Favourites.MAX, Favourites.decode(Favourites.encode((1..14).map { "$it" })).size)
    }

    @Test
    fun addsAtTheEndOnlyWhenNewAndThereIsRoom() {
        assertEquals(listOf("a", "b", "c"), Favourites.add(listOf("a", "b"), "c"))
        assertEquals(listOf("a", "b"), Favourites.add(listOf("a", "b"), "a"))
        val full = (1..Favourites.MAX).map { "$it" }
        assertFalse(Favourites.canAdd(full, "new"))
        assertEquals(full, Favourites.add(full, "new"))
        assertTrue(Favourites.canAdd(full.drop(1), "new"))
    }

    @Test
    fun removesAndMoves() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "c", "d"), Favourites.remove(list, "b"))
        assertEquals(listOf("c", "a", "b", "d"), Favourites.moveToTop(list, "c"))
        assertEquals(listOf("a", "c", "d", "b"), Favourites.moveToBottom(list, "b"))
        // Unknown apps leave the list as it is.
        assertEquals(list, Favourites.remove(list, "x"))
        assertEquals(list, Favourites.moveToTop(list, "x"))
        assertEquals(list, Favourites.moveToBottom(list, "x"))
    }
}
