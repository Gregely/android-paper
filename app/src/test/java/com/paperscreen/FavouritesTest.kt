package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class FavouritesTest {

    @Test
    fun missingStorageIsFiveEmptySlots() {
        assertEquals(List(Favourites.SLOTS) { null }, Favourites.decode(null))
    }

    @Test
    fun roundTripKeepsSlotPositions() {
        val slots = listOf("a/.A", null, "b/.B", null, "c/.C")
        assertEquals(slots, Favourites.decode(Favourites.encode(slots)))
    }

    @Test
    fun shortOrLongInputIsNormalisedToFiveSlots() {
        assertEquals(listOf("a/.A", null, null, null, null), Favourites.decode("a/.A"))
        assertEquals(5, Favourites.decode("1\n2\n3\n4\n5\n6\n7").size)
    }
}
