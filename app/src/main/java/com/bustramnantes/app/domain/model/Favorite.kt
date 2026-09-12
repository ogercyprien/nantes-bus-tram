package com.bustramnantes.app.domain.model

/** Granularity of a saved [Favorite]. */
enum class FavoriteType {
    /** Next 3 departures across all lines and directions at the stop. */
    STOP,

    /** Next 3 departures for one line at the stop (both directions). */
    STOP_LINE,

    /** Next 3 departures for one line and one direction only. */
    STOP_LINE_DIRECTION,
}

/**
 * A user-saved favorite, fully local, never sent to any server (Room table `favorites`).
 */
data class Favorite(
    val id: String,
    val type: FavoriteType,
    /** GTFS StopPlace id (`FR_NAOLIB:StopPlace:<n>`); may be any member of a merged [Stop]. */
    val stopId: String,
    val stopName: String,
    val lineId: String?,
    val lineShortName: String?,
    val lineColor: String?,
    val lineTextColor: String?,
    /** Direction *code* (e.g. `"0"`/`"1"`) - the stable key used to match live departures. */
    val direction: String?,
    /**
     * Human-readable direction name (e.g. `"Gare Sud"`) captured when the favorite was saved, so
     * the card can show "Direction Gare Sud" instead of the opaque code even outside service hours.
     * Null for non-direction favorites, or for favorites saved before this field existed.
     */
    val directionName: String?,
    val position: Int,
    val addedAt: Long,
)
