package com.bustramnantes.app.presentation.common.utils

import com.bustramnantes.app.domain.model.Departure
import com.bustramnantes.app.domain.util.normalizeForMatch

/**
 * The `"→ …"` label after a departure's time, shared by every list of departures (Stop Detail
 * blocks, favorite cards, the home widget) so they never disagree on the same run.
 *
 * - [fixedDirectionName] non-null: the surrounding block/card already names the direction (a Stop
 *   Detail line+direction block, a line+direction favorite, the widget). Only a **short run** is
 *   labelled, with where it actually stops ([shortRunDestination]); a full run gets no label.
 * - [fixedDirectionName] null: the list mixes directions (a whole-stop or whole-line favorite), so
 *   every row says where it goes: the run's own destination, else its direction's name.
 *
 * Returns null when there is nothing to show.
 */
fun Departure.destinationLabel(fixedDirectionName: String?): String? =
    if (fixedDirectionName != null) {
        shortRunDestination(fixedDirectionName)
    } else {
        destinationName?.takeIf { it.isNotBlank() } ?: direction.name.takeIf { it.isNotBlank() }
    }

/**
 * This run's own destination when it is a **short run** - it ends before the terminus its block is
 * named after (e.g. a tram 2 ending at Hôtel Dieu, listed under "Gare de Pont Rousseau") - else null.
 * A destination equal to the direction name, or to one branch of a dual `"A / B"` direction name
 * (each run of line 1 "Beaujoire / Babinière" goes to one of the two), is not a short run. Short runs
 * stay in their direction block, with this label on the row.
 */
fun Departure.shortRunDestination(directionName: String): String? {
    val destination = destinationName?.takeIf { it.isNotBlank() } ?: return null
    val target = normalizeForMatch(destination)
    val names = (listOf(directionName) + directionName.split("/")).map { normalizeForMatch(it.trim()) }
    return if (target in names || names.all { it.isBlank() }) null else destination
}
