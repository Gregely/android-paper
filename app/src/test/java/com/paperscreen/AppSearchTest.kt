package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {

    private val apps = listOf("Calculator", "Calendar", "Camera", "Google Calendar", "Pocket Casts", "Café Menu")

    private fun search(query: String) = AppSearch.filter(apps, query) { it }

    @Test
    fun emptyQueryKeepsEverythingInOrder() {
        assertEquals(apps, search("  "))
    }

    @Test
    fun prefixMatchesComeBeforeWordAndInnerMatches() {
        assertEquals(listOf("Calendar", "Google Calendar"), search("calen"))
        assertEquals(listOf("Camera", "Pocket Casts"), search("ca").filter { it == "Camera" || it == "Pocket Casts" })
        assertEquals("Calculator", search("ca").first())
    }

    @Test
    fun matchingIgnoresCaseAndAccents() {
        assertEquals(listOf("Café Menu"), search("CAFE"))
    }

    @Test
    fun innerMatchesAreFound() {
        assertEquals(listOf("Pocket Casts"), search("ocket"))
    }

    @Test
    fun noMatchIsEmpty() {
        assertEquals(emptyList<String>(), search("zzz"))
    }
}
