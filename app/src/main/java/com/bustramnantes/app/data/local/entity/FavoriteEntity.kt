package com.bustramnantes.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room-persisted user favorite, fully local, never sent to any server (table `favorites`).
 *
 * [id] is a UUID string. [stopId] is a GTFS StopPlace id (`FR_NAOLIB:StopPlace:<n>`). [lineId] is
 * the GTFS route id `FR_NAOLIB:Line:<short name>`. [type] mirrors [com.bustramnantes.app.domain.model.FavoriteType]'s
 * `name` (`"STOP"` | `"STOP_LINE"` | `"STOP_LINE_DIRECTION"`). [lineId]/[lineShortName]/
 * [lineColor]/[lineTextColor] are null for [com.bustramnantes.app.domain.model.FavoriteType.STOP].
 * [direction] is null unless type is `STOP_LINE_DIRECTION`. [position] is the user-defined sort
 * order. [addedAt] is an epoch-ms timestamp.
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: String,
    val type: String,
    val stopId: String,
    val stopName: String,
    val lineId: String?,
    val lineShortName: String?,
    val lineColor: String?,
    val lineTextColor: String?,
    val direction: String?,
    /** Human-readable direction name captured at save time (nullable; added in DB v2). */
    val directionName: String? = null,
    val position: Int,
    val addedAt: Long,
)
