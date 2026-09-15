package com.bustramnantes.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [normalizeForMatch]: the accent-insensitive folding behind diacritic-tolerant search. */
class TextNormalizationTest {

    @Test
    fun `strips accents and lowercases`() {
        assertEquals(normalizeForMatch("leon jost"), normalizeForMatch("Léon Jost"))
        assertEquals("aeroport", normalizeForMatch("Aéroport"))
    }

    @Test
    fun `accent-free prefix matches an accented name`() {
        assertTrue(normalizeForMatch("Aéroport").contains(normalizeForMatch("aerop")))
        assertTrue(normalizeForMatch("Beauséjour").contains(normalizeForMatch("beausejour")))
    }

    @Test
    fun `collapses whitespace runs and trims`() {
        assertEquals("gare sud", normalizeForMatch("  Gare   Sud "))
    }
}
