package com.bustramnantes.app.domain.util

import com.bustramnantes.app.domain.model.ItineraryTimePreference
import com.bustramnantes.app.domain.model.ItineraryTimePreference.ArriveBy
import com.bustramnantes.app.domain.model.ItineraryTimePreference.DepartAt
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItineraryTimeShiftTest {

    private val now = LocalDateTime.parse("2026-10-10T09:41:37")

    private fun at(iso: String) = LocalDateTime.parse(iso)

    private fun shift(preference: ItineraryTimePreference, delta: Int, now: LocalDateTime = this.now) =
        shiftItineraryTime(preference, delta, now)

    @Test
    fun `partir a keeps its mode and moves by 15 minutes both ways`() {
        val pref = DepartAt(at("2026-10-10T10:17"))
        assertEquals(DepartAt(at("2026-10-10T10:02")), shift(pref, -15))
        assertEquals(DepartAt(at("2026-10-10T10:32")), shift(pref, 15))
    }

    @Test
    fun `arriver a keeps its mode and moves by 15 minutes both ways`() {
        val pref = ArriveBy(at("2026-10-10T21:05"))
        assertEquals(ArriveBy(at("2026-10-10T20:50")), shift(pref, -15))
        assertEquals(ArriveBy(at("2026-10-10T21:20")), shift(pref, 15))
    }

    @Test
    fun `maintenant cannot go earlier`() {
        assertNull(shift(ItineraryTimePreference.Now, -15))
    }

    @Test
    fun `maintenant later becomes partir a now plus 15 to the minute`() {
        assertEquals(DepartAt(at("2026-10-10T09:56")), shift(ItineraryTimePreference.Now, 15))
    }

    @Test
    fun `later rolls the date over past midnight`() {
        assertEquals(DepartAt(at("2026-10-11T00:05")), shift(DepartAt(at("2026-10-10T23:50")), 15))
        assertEquals(
            DepartAt(at("2026-10-11T00:10")),
            shift(ItineraryTimePreference.Now, 15, now = at("2026-10-10T23:55:59")),
        )
    }

    @Test
    fun `earlier rolls the date back past midnight`() {
        assertEquals(ArriveBy(at("2026-10-11T23:55")), shift(ArriveBy(at("2026-10-12T00:10")), -15))
    }

    @Test
    fun `month and year boundaries roll over too`() {
        val now = at("2026-12-31T20:00")
        assertEquals(DepartAt(at("2027-01-01T00:02")), shift(DepartAt(at("2026-12-31T23:47")), 15, now))
    }

    @Test
    fun `earlier than today is refused like the date picker would`() {
        assertNull(shift(DepartAt(at("2026-10-10T00:10")), -15))
    }

    @Test
    fun `later than the date picker cap is refused`() {
        val lastDay = now.date.lastSchedulableDate()
        val lastSlot = LocalDateTime(lastDay, LocalTime(23, 50))
        assertNull(shift(DepartAt(lastSlot), 15))
        assertEquals(DepartAt(at("${lastDay}T23:55")), shift(DepartAt(at("${lastDay}T23:40")), 15))
    }

    @Test
    fun `a DST gap time is normalised in the network zone`() {
        // 2027-03-28 02:00 -> 03:00 in Europe/Paris: 01:50 + 15 min lands at 03:05 wall-clock.
        val paris = TimeZone.of("Europe/Paris")
        val now = at("2027-03-27T12:00")
        assertEquals(
            DepartAt(at("2027-03-28T03:05")),
            shiftItineraryTime(DepartAt(at("2027-03-28T01:50")), 15, now, paris),
        )
    }
}
