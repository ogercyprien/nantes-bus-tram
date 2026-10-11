package com.bustramnantes.app.data.remote.dto

import com.bustramnantes.app.data.remote.dto.geocoding.GeocodingResponseDto
import com.bustramnantes.app.data.remote.opendata.GtfsRecordsDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes **real captured responses** (`src/test/resources/fixtures/`) with the production `Json`
 * config, so a DTO written against an assumed shape fails here rather than silently in the app.
 * (The Okina SIRI / GTFS-RT fixtures are decoded by `:transit`'s `RealtimeTest`.)
 *
 * Uses the same `Json` config as `di/NetworkModule.kt`'s production bean, deliberately not imported
 * from it (that class depends on Hilt) - keep the two in sync.
 */
class LiveApiShapeDecodingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "missing fixture: $name" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `geocoding decodes a standard BAN FeatureCollection`() {
        val dto = json.decodeFromString<GeocodingResponseDto>(fixture("geocoding.json"))
        assertTrue("expected at least one geocoding result", dto.features.isNotEmpty())
        assertTrue(dto.features.first().properties.label.contains("Commerce"))
    }

    /** Captured live: one publication, the zip's file id is the change key. */
    @Test
    fun `open-data GTFS records expose the file id, url and validity window`() {
        val dto = json.decodeFromString<GtfsRecordsDto>(fixture("opendata_gtfs_records.json"))
        val record = dto.results.single()
        assertEquals("0cc0469a72de54ee045cb66d1a21de9e", record.file?.id)
        assertTrue(record.file?.url.orEmpty().endsWith("/files/0cc0469a72de54ee045cb66d1a21de9e"))
        assertEquals("2026-10-09", record.validFrom)
        assertEquals("2027-01-09", record.validUntil)
    }
}
