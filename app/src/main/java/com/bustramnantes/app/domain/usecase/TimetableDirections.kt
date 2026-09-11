package com.bustramnantes.app.domain.usecase

import com.bustramnantes.app.domain.model.Line
import com.bustramnantes.app.domain.util.normalizeForMatch

/**
 * The timetable screen's route carries a **1-based** direction id, while the rest of the app carries the **0-based** direction code (`Direction.code` = GTFS
 * `direction_id`). This shifts it (`code + 1`), returning `null` when the code isn't numeric - in
 * which case the caller hides the timetable entry point. */
fun directionIdFromCode(code: String): Int? = code.trim().toIntOrNull()?.plus(1)

/**
 * Resolves the 1-based [TimetableRequest.directionId] for an **itinerary leg**, which carries only a
 * direction *name* (no code). Matches the name against the [line]'s directions
 * (accent-insensitive; dual `"A / B"` destination names match on either slash-component in either
 * direction) to recover the direction code, then shifts it. Returns `null` when [line] is unknown or no
 * direction matches - the caller then hides the leg's timetable button. */
fun itineraryDirectionId(line: Line?, directionName: String): Int? {
    val directions = line?.directions?.takeIf { it.isNotEmpty() } ?: return null
    val target = normalizeForMatch(directionName)
    val match = directions.firstOrNull { normalizeForMatch(it.name) == target }
        ?: directions.firstOrNull { dir ->
            val dirParts = dir.name.split("/").map { normalizeForMatch(it.trim()) }
            val legParts = directionName.split("/").map { normalizeForMatch(it.trim()) }
            dirParts.any { it in legParts }
        }
    return match?.let { directionIdFromCode(it.code) }
}
