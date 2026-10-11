package com.bustramnantes.app.data.repository

import com.bustramnantes.app.data.local.entity.FavoriteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers [rekeyFavorite]: a favorite whose StopPlace a new publication retired moves onto the right successor. */
class FavoriteRekeyerTest {

    // A "Chanzy" StopPlace split in two by a newer publication: one side for C1/NN, the other for C6.
    private val north = RekeyCandidate("FR_NAOLIB:StopPlace:1040", "Chanzy", mapOf("C1" to setOf("0", "1"), "NN" to setOf("0")))
    private val south = RekeyCandidate("FR_NAOLIB:StopPlace:1078", "Chanzy", mapOf("C6" to setOf("0", "1")))

    private fun favorite(stopName: String, line: String? = null, direction: String? = null, legacy: String? = null) = FavoriteEntity(
        id = "f", type = if (line == null) "STOP" else if (direction == null) "STOP_LINE" else "STOP_LINE_DIRECTION",
        stopId = "FR_NAOLIB:StopPlace:59", stopName = stopName,
        lineId = line?.let { "FR_NAOLIB:Line:$it" }, lineShortName = line,
        lineColor = null, lineTextColor = null, direction = direction, position = 0, addedAt = 0,
        legacyStopId = legacy,
    )

    @Test
    fun `line favorite goes to the stop place serving its line`() {
        val out = rekeyFavorite(favorite("Chanzy", line = "C6"), listOf(north, south))!!
        assertEquals("FR_NAOLIB:StopPlace:1078", out.stopId)
        assertEquals("FR_NAOLIB:StopPlace:59", out.legacyStopId)
        assertEquals("FR_NAOLIB:Line:C6", out.lineId)
    }

    @Test
    fun `direction favorite prefers the stop place boardable in that direction`() {
        val outbound = RekeyCandidate("A", "Chanzy", mapOf("NN" to setOf("1")))
        val out = rekeyFavorite(favorite("Chanzy", line = "NN", direction = "0"), listOf(outbound, north))!!
        assertEquals(north.stopPlaceId, out.stopId)
    }

    @Test
    fun `stop favorite keeps the stop place bearing its saved name`() {
        val other = RekeyCandidate("B", "Chanzy Nord", emptyMap())
        assertEquals(north.stopPlaceId, rekeyFavorite(favorite("Chanzy"), listOf(other, north))!!.stopId)
    }

    @Test
    fun `falls back to the first candidate, and to nothing without candidates`() {
        assertEquals(south.stopPlaceId, rekeyFavorite(favorite("Elsewhere"), listOf(south, north))!!.stopId)
        assertNull(rekeyFavorite(favorite("Chanzy"), emptyList()))
    }

    @Test
    fun `a favorite re-keyed twice keeps the id it was saved with`() {
        val out = rekeyFavorite(favorite("Chanzy", legacy = "FR_NAOLIB:StopPlace:12"), listOf(north))!!
        assertEquals("FR_NAOLIB:StopPlace:12", out.legacyStopId)
    }
}
