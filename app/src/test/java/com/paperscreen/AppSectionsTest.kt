package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSectionsTest {

    private fun sections(vararg labels: String) =
        AppSections.build(labels.toList()) { it }.map { it.letter to it.items }

    @Test
    fun groupsByFirstLetter() {
        assertEquals(
            listOf("C" to listOf("Calendar", "Camera"), "M" to listOf("Maps")),
            sections("Calendar", "Camera", "Maps"),
        )
    }

    @Test
    fun accentedNamesGoUnderTheirBaseLetter() {
        assertEquals(listOf("E" to listOf("Écran", "Email")), sections("Écran", "Email"))
    }

    @Test
    fun digitsAndSymbolsGoUnderHashAtTheEnd() {
        assertEquals(
            listOf("A" to listOf("Alarm"), "#" to listOf("1Password", "@Home")),
            sections("1Password", "@Home", "Alarm"),
        )
    }

    @Test
    fun lowercaseNamesShareTheUppercaseHeading() {
        assertEquals(listOf("K" to listOf("Keep", "kindle")), sections("Keep", "kindle"))
    }
}
