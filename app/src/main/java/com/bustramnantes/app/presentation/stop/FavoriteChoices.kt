package com.bustramnantes.app.presentation.stop

import com.bustramnantes.app.domain.model.Direction
import com.bustramnantes.app.domain.model.Favorite
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.model.TransportMode

/** A distinct line (across both directions) available for the granular favorite choices. */
data class FavoriteLineChoice(
    val lineId: String,
    val lineShortName: String,
    val lineColor: String,
    val lineTextColor: String,
    val transportMode: TransportMode,
    val directions: List<Direction>,
)

fun List<DepartureGroup>.toLineChoices(): List<FavoriteLineChoice> =
    groupBy { it.lineId }
        .map { (lineId, groups) ->
            val first = groups.first()
            FavoriteLineChoice(
                lineId = lineId,
                lineShortName = first.lineNumber,
                lineColor = first.lineColor,
                lineTextColor = first.lineTextColor,
                transportMode = first.transportMode,
                directions = groups.map { it.direction }.distinctBy { it.code },
            )
        }

/**
 * What the favorite picker has selected. [lineId] is ignored for [FavoriteType.STOP] and
 * [directionCode] for anything but [FavoriteType.STOP_LINE_DIRECTION] - see [savedLineId] /
 * [savedDirection] for the values a favorite would actually store.
 */
data class FavoriteSelection(
    val type: FavoriteType,
    val lineId: String?,
    val directionCode: String?,
) {
    val savedLineId: String? get() = lineId.takeIf { type != FavoriteType.STOP }
    val savedDirection: String? get() = directionCode.takeIf { type == FavoriteType.STOP_LINE_DIRECTION }
}

/** The picker selection describing an existing [favorite] (the editor opens on it). */
fun selectionOf(favorite: Favorite): FavoriteSelection =
    FavoriteSelection(type = favorite.type, lineId = favorite.lineId, directionCode = favorite.direction)

/**
 * The saved favorite (other than [exceptId], the one being edited) with exactly [selection]'s
 * configuration, if any. Favorites are told apart by (line, direction): STOP = (null, null),
 * STOP_LINE = (line, null), STOP_LINE_DIRECTION = (line, direction). Overlaps (a whole-stop
 * favorite next to a line one) are fine; only an exact twin is a duplicate.
 */
fun List<Favorite>.duplicateOf(selection: FavoriteSelection, exceptId: String? = null): Favorite? =
    find { it.id != exceptId && it.lineId == selection.savedLineId && it.direction == selection.savedDirection }

/**
 * Where the "Ajouter un favori" picker opens: the first configuration not saved yet, in the order
 * whole stop, then for each line (the [focusedLineId] one first) the whole line and each of its
 * directions ([focusedDirection] first). When everything is already saved, or no line is known yet,
 * it falls back to the focused line (or the first one) so the line chips still have a pick.
 */
fun initialAddSelection(
    lineChoices: List<FavoriteLineChoice>,
    existing: List<Favorite>,
    focusedLineId: String?,
    focusedDirection: String?,
): FavoriteSelection {
    val lines = lineChoices.sortedByDescending { it.lineId == focusedLineId }
    val candidates = buildList {
        add(FavoriteSelection(FavoriteType.STOP, null, null))
        lines.forEach { line ->
            val directions = line.directions.sortedByDescending { it.code == focusedDirection && line.lineId == focusedLineId }
            add(FavoriteSelection(FavoriteType.STOP_LINE, line.lineId, directions.firstOrNull()?.code))
            directions.forEach { add(FavoriteSelection(FavoriteType.STOP_LINE_DIRECTION, line.lineId, it.code)) }
        }
    }
    val fallbackLine = lines.firstOrNull()
    val fallback = FavoriteSelection(FavoriteType.STOP, fallbackLine?.lineId, fallbackLine?.directions?.firstOrNull()?.code)
    return candidates.firstOrNull { existing.duplicateOf(it) == null }
        // A STOP candidate keeps the default line chip ready in case the user switches type.
        ?.let { if (it.type == FavoriteType.STOP) fallback else it }
        ?: fallback
}

/** One-line description of what a favorite at this stop follows, for the "Favoris à …" list. */
fun favoriteScopeLabel(favorite: Favorite): String = when (favorite.type) {
    FavoriteType.STOP -> "Tous les départs"
    FavoriteType.STOP_LINE -> "Les deux directions"
    FavoriteType.STOP_LINE_DIRECTION -> "→ ${favorite.directionName ?: favorite.direction.orEmpty()}"
}
