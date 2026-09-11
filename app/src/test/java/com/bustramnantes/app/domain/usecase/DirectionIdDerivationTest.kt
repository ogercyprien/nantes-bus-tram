package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the two ways a 1-based API `directionId` is recovered. */
class DirectionIdDerivationTest {

    private fun line(vararg directions: Direction) = Line(
        id = "2414",
        shortName = "C2",
        longName = "Chronobus C2",
        color = "0072BC",
        textColor = "FFFFFF",
        mode = TransportMode.BUS,
        directions = directions.toList(),
    )

    @Test
    fun `zero-based code shifts to one-based id`() {
        assertEquals(1, directionIdFromCode("0"))
        assertEquals(2, directionIdFromCode("1"))
        assertEquals(2, directionIdFromCode(" 1 "))
    }

    @Test
    fun `non-numeric direction code yields null`() {
        assertNull(directionIdFromCode(""))
        assertNull(directionIdFromCode("A"))
    }

    @Test
    fun `itinerary direction id matches by exact name (accent-insensitive)`() {
        val l = line(Direction("0", "Le Cardo"), Direction("1", "Gare Sud"))
        assertEquals(1, itineraryDirectionId(l, "Le Cardo"))
        assertEquals(2, itineraryDirectionId(l, "Gare Sud"))
        // Accent + case folding via normalizeForMatch.
        assertEquals(1, itineraryDirectionId(line(Direction("0", "Hôtel Dieu")), "hotel dieu"))
    }

    @Test
    fun `itinerary direction id matches a dual destination on either slash-component`() {
        val l = line(Direction("0", "Hôtel Dieu / Saupin"), Direction("1", "Gare Sud"))
        // Leg carries just one of the dual destinations.
        assertEquals(1, itineraryDirectionId(l, "Saupin"))
        assertEquals(1, itineraryDirectionId(l, "Hôtel Dieu"))
    }

    @Test
    fun `itinerary direction id is null when line is unknown or no direction matches`() {
        assertNull(itineraryDirectionId(null, "Le Cardo"))
        assertNull(itineraryDirectionId(line(), "Le Cardo"))
        assertNull(itineraryDirectionId(line(Direction("0", "Le Cardo")), "Beaujoire"))
    }
}
