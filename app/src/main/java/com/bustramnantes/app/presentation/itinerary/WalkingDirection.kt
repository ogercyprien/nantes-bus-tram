package com.bustramnantes.app.presentation.itinerary

import com.bustramnantes.app.domain.model.JourneySection
import kotlin.math.roundToLong

/** One 45°-wide compass sector: the arrow glyph plus its French "vers le/l' ..." phrase. */
private data class CompassSector(val arrow: String, val phrase: String)

private val COMPASS_SECTORS = listOf(
    CompassSector("↑", "vers le nord"), // 337.5°..22.5°
    CompassSector("↗", "vers le nord-est"), // 22.5°..67.5°
    CompassSector("→", "vers l'est"), // 67.5°..112.5°
    CompassSector("↘", "vers le sud-est"), // 112.5°..157.5°
    CompassSector("↓", "vers le sud"), // 157.5°..202.5°
    CompassSector("↙", "vers le sud-ouest"), // 202.5°..247.5°
    CompassSector("←", "vers l'ouest"), // 247.5°..292.5°
    CompassSector("↖", "vers le nord-ouest"), // 292.5°..337.5°
)

/**
 * Maps a compass bearing (degrees, `0` = north, clockwise, per `MagneticDirection`)
 * to one of 8 arrow glyphs + a French direction phrase - an 8-way compass is enough.
 */
private fun compassSectorFor(degrees: Double): CompassSector {
    val normalized = ((degrees % 360.0) + 360.0) % 360.0
    val index = (((normalized + 22.5) / 45.0).toInt()) % 8
    return COMPASS_SECTORS[index]
}

/**
 * Renders a walking leg, e.g. `"🚶 Marchez 320 m · ↑ vers le nord · ~4 min"`,
 * derived from [JourneySection.Walking.magneticDirection] and [JourneySection.Walking.distanceMeters].
 */
fun formatWalkingSummary(section: JourneySection.Walking): String {
    val sector = compassSectorFor(section.magneticDirection)
    val minutes = (section.durationSeconds / 60.0).roundToLong().coerceAtLeast(0L)
    return "🚶 Marchez ${section.distanceMeters} m · ${sector.arrow} ${sector.phrase} · ~$minutes min"
}
