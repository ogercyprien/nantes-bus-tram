package com.bustramnantes.app.domain.util

import com.bustramnantes.app.domain.model.ConcernedStop
import com.bustramnantes.app.domain.model.Disruption
import com.bustramnantes.app.domain.model.DisruptionCause
import com.bustramnantes.app.domain.model.DisruptionPeriod
import com.bustramnantes.app.domain.model.DisruptionTarget
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.model.JourneySection
import com.bustramnantes.app.domain.model.TimeSourceType
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.presentation.common.utils.formatDisruptionWhen
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisruptionRelevanceTest {

    /** Monday 2026-10-12 11:00 Paris. */
    private val now = Instant.parse("2026-10-12T09:00:00Z")
    private val c6 = "FR_NAOLIB:Line:C6"
    private val l81 = "FR_NAOLIB:Line:81"
    private val bonde = "FR_NAOLIB:StopPlace:10"
    private val other = "FR_NAOLIB:StopPlace:11"

    private fun d(id: String, vararg targets: DisruptionTarget, start: Instant? = now - 1.days, end: Instant? = now + 10.days) =
        Disruption(id, id, "", null, listOf(DisruptionPeriod(start, end)), targets.toList(), DisruptionCause.OTHER)

    private val movedStop = d("moved", DisruptionTarget(c6, bonde))
    private val lineWorks = d("works", DisruptionTarget(l81, null))
    private val anyLineHere = d("here", DisruptionTarget(null, bonde))
    private val tomorrow = d("tomorrow", DisruptionTarget(c6, null), start = now + 22.hours, end = now + 30.hours)
    private val nextMonth = d("later", DisruptionTarget(c6, null), start = now + 20.days, end = now + 25.days)
    private val over = d("over", DisruptionTarget(c6, null), start = now - 3.days, end = now - 1.days)
    private val all = listOf(movedStop, lineWorks, anyLineHere, tomorrow, nextMonth, over)

    @Test
    fun `current keeps notices in force and starting within three days, in force first`() {
        assertEquals(listOf("moved", "works", "here", "tomorrow"), all.current(now).map { it.id })
    }

    @Test
    fun `stop relevance splits stop targets from whole-line ones`() {
        val r = all.forStop(listOf(bonde), listOf(c6, l81), now)
        assertEquals(listOf("moved", "here"), r.atStop.map { it.id })
        assertEquals(listOf("works", "tomorrow"), r.onLines.map { it.id })
        assertTrue(all.forStop(listOf(other), listOf("FR_NAOLIB:Line:1"), now).isEmpty)
    }

    @Test
    fun `line favorite only gets its line, there or as a whole`() {
        val fav = Favorite("f", FavoriteType.STOP_LINE, bonde, "Bonde", l81, "81", null, null, null, null, 0, 0)
        assertEquals(listOf("works", "here"), all.forFavorite(fav, listOf(bonde), listOf(c6, l81), now).map { it.id })
        val stopFav = fav.copy(type = FavoriteType.STOP, lineId = null)
        assertEquals(4, all.forFavorite(stopFav, listOf(bonde), listOf(c6, l81), now).size)
    }

    @Test
    fun `a leg gets notices on its line or its ends while it runs`() {
        fun stop(id: String) = ConcernedStop(id, id, 0.0, 0.0, null)
        val leg = JourneySection.Transit(
            lineId = c6, lineNumber = "C6", lineColor = "", lineTextColor = "", transportMode = TransportMode.BUS,
            directionName = "", concernedStops = listOf(stop(other), stop("mid"), stop(bonde)), tripGeometryWkt = "",
            timeSourceType = TimeSourceType.THEORICAL, departureTime = now + 23.hours, arrivalTime = now + 23.hours + 20.minutes,
        )
        assertEquals(listOf("moved", "here", "tomorrow"), all.forLeg(leg).map { it.id })
        val midOnly = d("mid", DisruptionTarget(c6, "mid"))
        assertTrue(listOf(midOnly).forLeg(leg).isEmpty())
    }

    @Test
    fun `timing wording`() {
        assertEquals("Jusqu'au 22/10", formatDisruptionWhen(movedStop, now))
        assertEquals("À partir de demain 09:00", formatDisruptionWhen(tomorrow, now))
        assertEquals("À partir du dim. 01/11 à 10:00", formatDisruptionWhen(nextMonth, now))
        assertEquals("Jusqu'à 21:30", formatDisruptionWhen(d("x", end = Instant.parse("2026-10-12T19:30:00Z")), now))
        assertEquals("En cours", formatDisruptionWhen(d("y", end = null), now))
    }
}
