package com.bustramnantes.transit

import com.bustramnantes.transit.gtfs.GtfsPublication
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

class GtfsPublicationTest {
    private val id = "0cc0469a72de54ee045cb66d1a21de9e"

    @Test
    fun `a republication under the same file id is a new publication`() {
        // The real case: 2026-10-11 and 2026-11-04 publications, same portal file id.
        val october = GtfsPublication.key(id, "2026-10-09", "2027-01-09")
        val november = GtfsPublication.key(id, "2026-11-04", "2027-02-05")
        assertNotEquals(october, november)
        assertNotEquals(id, november) // networks installed before the fix (keyed by the bare id) refresh once
    }

    @Test
    fun `the key is stable and file-name safe`() {
        assertEquals("${id}_2026-11-04_2027-02-05", GtfsPublication.key(id, "2026-11-04", "2027-02-05"))
        assertEquals("${id}_-_-", GtfsPublication.key(id, null, ""))
    }
}
