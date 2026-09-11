package com.bustramnantes.app.presentation.timetable

import com.bustramnantes.app.domain.model.TimetableEntry
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [groupIntoHourBlocks] - the lossless collapsing of identical consecutive hours. */
class TimetableHourBlocksTest {

    private fun entry(hour: Int, minute: Int, nextDay: Boolean = false, dest: String? = "Le Cardo") =
        TimetableEntry(LocalTime(hour, minute), nextDay, dest)

    @Test
    fun `consecutive hours with identical minutes collapse into one range block`() {
        val entries = (12..16).flatMap { h -> listOf(entry(h, 7), entry(h, 17), entry(h, 27)) }
        val blocks = groupIntoHourBlocks(entries)
        assertEquals(1, blocks.size)
        assertEquals(12, blocks[0].startHour)
        assertEquals(16, blocks[0].endHour)
        assertEquals("12h–16h", blocks[0].label)
        assertEquals(listOf(7, 17, 27), blocks[0].entries.map { it.time.minute })
    }

    @Test
    fun `a differing minute set breaks the run`() {
        val entries = listOf(
            entry(12, 7), entry(12, 17),
            entry(13, 7), entry(13, 17),
            entry(14, 5), entry(14, 20), // different minutes
        )
        val blocks = groupIntoHourBlocks(entries)
        assertEquals(2, blocks.size)
        assertEquals("12h–13h", blocks[0].label)
        assertEquals("14h", blocks[1].label)
    }

    @Test
    fun `differing per-minute destination breaks the run even when minutes match`() {
        val entries = listOf(
            entry(12, 7, dest = "A"), entry(13, 7, dest = "B"),
        )
        val blocks = groupIntoHourBlocks(entries)
        assertEquals(2, blocks.size)
    }

    @Test
    fun `after-midnight hours are never merged with the previous day and sort last`() {
        val entries = listOf(
            entry(23, 21), entry(23, 51),
            entry(0, 21, nextDay = true), entry(0, 51, nextDay = true),
        )
        val blocks = groupIntoHourBlocks(entries)
        assertEquals(2, blocks.size)
        assertEquals(false, blocks[0].nextDay)
        assertEquals("23h", blocks[0].label)
        assertTrue(blocks[1].nextDay)
        assertEquals("00h", blocks[1].label)
    }

    @Test
    fun `isolateHour keeps that hour as its own block`() {
        val entries = (12..16).flatMap { h -> listOf(entry(h, 7), entry(h, 17)) }
        // Isolate 14h (a highlighted departure lands there): 12-13, 14, 15-16.
        val blocks = groupIntoHourBlocks(entries, isolateHour = false to 14)
        assertEquals(listOf("12h–13h", "14h", "15h–16h"), blocks.map { it.label })
    }

    @Test
    fun `empty entries yield no blocks`() {
        assertTrue(groupIntoHourBlocks(emptyList()).isEmpty())
    }
}
