package com.bustramnantes.app.presentation.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bustramnantes.app.domain.model.FavoriteType
import com.bustramnantes.app.domain.model.TransportMode
import com.bustramnantes.app.presentation.common.components.DepartureRow
import com.bustramnantes.app.presentation.common.components.DisruptionAmber
import com.bustramnantes.app.presentation.common.components.LineBadgeWithAlert
import com.bustramnantes.app.presentation.common.components.EndOfServiceText
import com.bustramnantes.app.presentation.common.components.LineBadge
import com.bustramnantes.app.presentation.common.components.LineBadgeSize
import com.bustramnantes.app.presentation.common.utils.END_OF_SERVICE_LABEL
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong

/**
 * A single favorite card on the Home dashboard (card layout):
 * header (stop name + line badge + direction), departure rows, real-time indicator, last-updated
 * footer, and a `"Fin de service"` state.
 *
 * Interaction: tap opens Stop Detail; long-press opens the "Voir l'arrêt / Modifier / Réorganiser /
 * Supprimer" context menu ("Modifier" opens Stop Detail on the favorite's editor). While [isReorderMode] is `true`, long-press is disabled
 * and up/down move controls are shown instead - see `HomeScreen.kt` for why this project uses an
 * up/down fallback rather than a full pointer-drag gesture for reordering.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoriteCard(
    card: FavoriteCardUiState,
    isReorderMode: Boolean,
    isMenuOpen: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favorite = card.favorite
    val accessibleDescription = accessibleCardDescription(card)

    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onEvent(HomeEvent.CardClicked(favorite.id)) },
                    onLongClick = { if (!isReorderMode) onEvent(HomeEvent.CardLongPressed(favorite.id)) },
                )
                .semantics(mergeDescendants = true) { contentDescription = accessibleDescription },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = favorite.stopName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val lineShortName = favorite.lineShortName
                    val lineColor = favorite.lineColor
                    val lineTextColor = favorite.lineTextColor
                    if (favorite.lineId != null && lineShortName != null && lineColor != null && lineTextColor != null) {
                        LineBadgeWithAlert(
                            shortName = lineShortName,
                            backgroundColorHex = lineColor,
                            textColorHex = lineTextColor,
                            mode = card.lineMode ?: TransportMode.BUS,
                            hasDisruption = favorite.lineId in card.disruptedLineIds,
                            size = LineBadgeSize.Small,
                        )
                    }
                }
                // Prefer the saved human-readable direction name; fall back to the raw code for
                // favorites saved before directionName existed (DB v1).
                (favorite.directionName ?: favorite.direction)?.let { direction ->
                    Text(
                        text = "Direction $direction",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                when {
                    card.isError -> Text(
                        text = "Départs indisponibles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    card.departures.isEmpty() -> EndOfServiceText()
                    // Vertical list, one departure per line, so it stays readable. For a whole-stop
                    // favorite (no line chosen), each row is prefixed with its line badge - otherwise
                    // a "next in 1 min" is useless without knowing which line it is.
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // A line+direction favorite names its direction in the header, so rows only
                        // label short runs, against the same canonical direction name as the Stop
                        // Detail block; a whole-stop or whole-line favorite mixes directions, so every
                        // row says where it goes. A whole-stop favorite also prefixes each row with
                        // its line badge.
                        val directionIsFixed = favorite.type == FavoriteType.STOP_LINE_DIRECTION
                        card.departures.forEach { departure ->
                            DepartureRow(
                                departure = departure,
                                fixedDirectionName = departure.direction.name.takeIf { directionIsFixed },
                                showLineBadge = favorite.type == FavoriteType.STOP,
                                hasDisruption = departure.lineId in card.disruptedLineIds,
                            )
                        }
                    }
                }

                // The most relevant disruption notice, in one line (the stop sheet has them all).
                card.disruptions.firstOrNull()?.let { first ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = DisruptionAmber,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = first.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp).weight(1f),
                        )
                        if (card.disruptions.size > 1) {
                            Text(
                                text = "+${card.disruptions.size - 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }

                card.lastUpdated?.let { lastUpdated ->
                    Text(
                        text = "Mis à jour à ${formatUpdatedAt(lastUpdated)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (isReorderMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { onEvent(HomeEvent.MoveFavoriteUp(favorite.id)) },
                            enabled = canMoveUp,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Monter ${favorite.stopName}",
                            )
                        }
                        IconButton(
                            onClick = { onEvent(HomeEvent.MoveFavoriteDown(favorite.id)) },
                            enabled = canMoveDown,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Descendre ${favorite.stopName}",
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = isMenuOpen && !isReorderMode,
            onDismissRequest = { onEvent(HomeEvent.CardMenuDismissed) },
        ) {
            DropdownMenuItem(
                text = { Text("Voir l'arrêt") },
                leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                onClick = { onEvent(HomeEvent.ViewStopClicked(favorite.id)) },
            )
            DropdownMenuItem(
                text = { Text("Modifier") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { onEvent(HomeEvent.EditFavoriteClicked(favorite.id)) },
            )
            DropdownMenuItem(
                text = { Text("Réorganiser") },
                leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                onClick = { onEvent(HomeEvent.ReorderModeEntered) },
            )
            DropdownMenuItem(
                text = { Text("Supprimer") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { onEvent(HomeEvent.DeleteFavoriteClicked(favorite.id)) },
            )
        }
    }
}

/**
 * TalkBack label, e.g. *"Commerce, ligne C2 direction Gare Sud,
 * prochain départ dans 3 minutes"*.
 */
private fun accessibleCardDescription(card: FavoriteCardUiState, now: Instant = Clock.System.now()): String {
    val favorite = card.favorite
    val base = buildString {
        append(favorite.stopName)
        favorite.lineShortName?.let { append(", ligne $it") }
        (favorite.directionName ?: favorite.direction)?.let { append(" direction $it") }
    }
    val status = when {
        card.isError -> "départs indisponibles"
        card.departures.isEmpty() -> END_OF_SERVICE_LABEL.lowercase()
        else -> {
            val next = card.departures.first()
            val minutes = ((next.nextDepartedTime - now).inWholeSeconds / 60.0).roundToLong().coerceAtLeast(0L)
            val unit = if (minutes > 1) "minutes" else "minute"
            "prochain départ dans $minutes $unit"
        }
    }
    val disruption = card.disruptions.firstOrNull()?.let { ", perturbation : ${it.title}" }.orEmpty()
    return "$base, $status$disruption"
}

private fun formatUpdatedAt(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val local = instant.toLocalDateTime(zone)
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}
